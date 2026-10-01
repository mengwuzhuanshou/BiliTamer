package com.tamer.bili.hooks;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.github.libxposed.api.XposedInterface;

/**
 * 首页 UI 布局调整（v1.7.0）：对齐国内版布局。
 *
 *  - 顶栏左侧头像（原本无点击行为）改为「我的」入口：透明点击层盖住头像区域，
 *    点击按「动作总线派发 → 宿主路由动作 → 合成点击（按渲染快照）→ tab 服务 → 深链」
 *    逐级兜底，详见 {@link #openMineEntry}（深链只开 GeneralActivity 独立壳，排最后）。
 *  - 顶栏搜索栏右侧空位加「消息」图标：点击走 bilibili://im/compat/home
 *    （实测落点=底栏「消息」同款页面）。国际版搜索区右侧本就留白（~370px），
 *    图标放右缘即呈现国内版「搜索框左缩 + 右侧消息」的观感，无需改 Compose 布局。
 *  - 底栏删 tab：hook tv.danmaku.bili.ui.main2.S.a()（tab 模型 provider）的返回列表，
 *    按 pageUrl 过滤「消息」/「我的」——底栏、pager、初始选中全部由该列表派生，
 *    一处过滤全链一致（BaseMainFrameFragment.pm() 消费它：index 分配、setTabs、
 *    pager 注册、im(pageUrl) 初始路由）。
 *
 * 锚点（6.4.0 实测）：
 *  - HomeAppBarLayout: tv.danmaku.bili.home.widget.top.HomeAppBarLayout（布局 AXML 真名）
 *  - tab 模型: BaseMainFrameFragment$o 字段 c(resource.x) → x.d = pageUrl
 *    （jadx 显示 f356164c/f357337d 为碰撞改名，真实名取末字母）
 * 探针：首次 setTabs/S.a() 打一条 tab 明细（pageUrl 列表），用于现场校准过滤规则。
 */
public final class HomeUxHooks {

    private final HookApi api;
    private final ClassLoader cl;

    private final AtomicBoolean tabListProbe = new AtomicBoolean(false);
    private final AtomicBoolean badgeProbe = new AtomicBoolean(false);

    /** 底栏要移除的 tab：pageUrl 前缀（运行时探针会打印真实值便于校准）。 */
    private static final String[] TAB_URL_REMOVE_MESSAGE = {"bilibili://im/"};
    private static final String[] TAB_URL_REMOVE_MINE = {"bilibili://user_center/mine"};

    /** HomeTabServiceImpl 实例（9100300 Compose 底栏 tab 管道 + q() 事件导航）。 */
    private final AtomicReference<Object> tabServiceRef = new AtomicReference<Object>(null);
    /** 从服务端配置里记下的「我的」tab 真实 url（顶栏头像入口导航用）。 */
    private volatile String mineTabUrl;
    /** 顶栏消息角标视图 + 轮询。 */
    private TextView msgBadgeView;
    private Runnable badgePoller;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    /** 底栏过滤后的 tab 状态（合成点击定位「我的」槽位用；默认=4 tab 第 4 格）。 */
    private volatile int mineSlotIndex = 3;
    private volatile int keptTabCount = 4;
    private volatile boolean mineTabKept = true;
    /**
     * 底栏**画出来**的那几个 tab（渲染级过滤之后的快照），只由底栏渲染钩子写：
     * {@code renderedTabCount}=条数，{@code renderedMineIndex}=「我的」在第几格，
     * 没有这一格就是 -1（-1 也含「钩子还没跑过」= 未知）。
     *
     * 为什么不能拿数据层的 keptTabCount/mineSlotIndex 去算点击位置：本模块的
     * 「隐藏底栏我的」是在**渲染参数**上把这一格去掉的（数据/pager 里仍然有，
     * 头像才还有得可派），所以底栏一旦隐藏，数据层槽位算出来的比例就落到
     * **左边那一格**上。6.6.0 实机翻车实录：数据 3 格（首页/关注/我的）→
     * (2+0.5)/3=0.833，而底栏只画了 2 格，0.833 命中的是「关注」——
     * 点头像进的是动态页。数据钩子（页面状态每次重建都进）也不许写这两个字段，
     * 否则「已隐藏」这个事实会被下一次状态构造悄悄抹掉。
     */
    private volatile int renderedTabCount = -1;
    private volatile int renderedMineIndex = -1;

    /**
     * 宿主「按路由切首页页」的动作派发（{@code HomeFrameViewModel.v0/w0/x0(<路由动作>)}）。
     * 缓存 {总线方法, (String,int,int) 构造器, 动作类}，与 VM 实例绑定后一次解析一次复用。
     *
     * 为什么不用 {@code PageRouteComponent}：那个类 6.6.0 反射
     * {@code getDeclaredConstructors()} 长度 0（实机日志 "ctors=0"），拿不到实例，
     * 构造器钩子永远不触发；而它自己做的就是把 url 包成路由动作再投总线
     * （dex 里 {@code b4(Intent)}/6.5.0 {@code T3}/6.4.0 {@code Q3}/6.3.0 {@code U3}
     * 的函数体就是 {@code iget <vm字段>; new bE1.g(url, 0, 2); vm.v0(...)} 三步），
     * 我们已经有 VM 实例（khomeVmRef），直接投同一条动作即可，少一层易碎的锚点。
     */
    private volatile Object[] routeDispatch;
    private volatile Class<?> routeDispatchFor;
    /**
     * 「我的」那一格的**匹配键**（形如 {@code bottom_tab_id=我的Bottom}），
     * 从底栏数据项的内层 tab 对象（6.6.0 {@code fE1.k}：b=name h=tab_id c=uri）读出来。
     * 宿主的路由动作处理器只认 url 上的这两个 query 参数
     * （{@code HomeFrameViewModel$dispatchAction$1} 的 {@code bE1.g} 分支：
     * 先按 {@code bottom_tab_id}/{@code bottom_tab_name} 在底栏列表里找下标），
     * 光给裸路由是空转，所以必须把宿主自己下发的那串 tab_id 带上。
     */
    private volatile String mineRouteKey;

    /** tab_host ComposeView 的资源 id（0x7f0938b4，设备版 uiautomator 实测同名同 id）。 */
    // tab_host ComposeView 的 id：随构建漂移（6.4.0=0x7f0938b4 / 6.5.0=0x7f0938d3）。
    // 常量只作快路径，找不到时按名字解析（见 tapBottomTab）。
    private static final int TAB_HOST_VIEW_ID = 0x7f0938d3;

    // ===== Compose content 探针（Pegasus 底栏专项 RE）=====
    /** 已探测过 setContent 的 loader（主 loader + main2 插件 loader 各试一次）。 */
    private final Set<ClassLoader> composeProbedLoaders =
            Collections.synchronizedSet(new HashSet<ClassLoader>());
    /** 已 hook 的 setContent 方法（跨 loader 去重）。 */
    private final Set<String> composeHooked =
            Collections.synchronizedSet(new HashSet<String>());
    /** 已打印过的 lambda 类名（去重限流；栈只随首次打印）。 */
    private final Set<String> composeLogged =
            Collections.synchronizedSet(new HashSet<String>());
    /** tab_host 类名链真名打印只做一次。 */
    private final AtomicBoolean composeTruthDone = new AtomicBoolean(false);

    // ===== khome 底栏 tab 模型探针/过滤（v1.7.0 Pegasus 专项 v2）=====
    /** HomeFrameViewModel 实例（真名类，状态中枢）。 */
    private final AtomicReference<Object> khomeVmRef = new AtomicReference<Object>(null);
    private final AtomicBoolean khomeFilterArmed = new AtomicBoolean(false);
    /** 运行时发现的（按形状）：页面 tab 状态类（KC1.e 形状）与其 List 字段。 */
    private volatile Class<?> khomePageStateCls;
    private volatile java.lang.reflect.Field khomeTabListField;
    private volatile Class<?> khomeTabItemCls;
    private volatile java.lang.reflect.Field khomeItemNameField; // KC1.d.b（String 路由名）
    /** 启动期一次性空跑「头像→我的」派发锚点是否已记录过。 */
    private volatile boolean tabAnchorLogged = false;
    /** tab 选中总线这一发在当前版本解析不出来（6.6.0+）：点击时不再重扫候选。 */
    private volatile boolean tabDispatchMiss = false;
    private final AtomicBoolean tabDispatchWarned = new AtomicBoolean(false);
    private int khomeProbeAttempts = 0;

    /**
     * tv.danmaku.bili.ui.main2.* 在插件化 ClassLoader 里加载（主加载器里的同名类
     * 是死拷贝——直接 hook 全部静默）。loadClass 嗅探到真实加载器后一次性重装
     * 全部 main2 漏斗。
     */
    private final AtomicBoolean main2FunnelsDone = new AtomicBoolean(false);
    private volatile ClassLoader mainUiLoader;

    public HomeUxHooks(HookApi api, ClassLoader cl) {
        this.api = api;
        this.cl = cl;
    }

    public void install() {
        installGroup("topbar overlays", new ThrowingAction() {
            @Override public void run() throws Throwable {
                installTopBarOverlays();
            }
        });
        installGroup("loader sniffer", new ThrowingAction() {
            @Override public void run() throws Throwable {
                installLoaderSniffer();
            }
        });
        installGroup("compose content probe", new ThrowingAction() {
            @Override public void run() throws Throwable {
                installComposeContentProbe(cl);
            }
        });
        installGroup("khome tab model probe", new ThrowingAction() {
            @Override public void run() throws Throwable {
                installKhomeTabProbe(cl);
            }
        });
        api.info("HomeUxHooks installed");
    }

    private interface ThrowingAction {
        void run() throws Throwable;
    }

    private void installGroup(String name, ThrowingAction a) {
        try {
            a.run();
            api.info("homeux: hook group ready: " + name);
        } catch (Throwable t) {
            api.error("homeux: hook group unavailable: " + name, t);
        }
    }

    // ===== main2 插件加载器嗅探 =====

    /**
     * 挂 java.lang.ClassLoader.loadClass(String,boolean)：MainFragment 首次加载时
     * 捕获其真实定义加载器（插件化后与主加载器不同），并一次性重装全部 main2 漏斗。
     * hooker 在类加载热路径上，非目标名快速返回。
     */
    private void installLoaderSniffer() throws Throwable {
        Class<?> clCls = Class.forName("java.lang.ClassLoader");
        final Method m = clCls.getDeclaredMethod("loadClass", String.class, boolean.class);
        m.setAccessible(true);
        api.deoptimize(m);
        api.addHook("homeux: loader sniffer", m, new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                try {
                    if (result != null && !main2FunnelsDone.get()
                            && "tv.danmaku.bili.ui.main2.MainFragment".equals(chain.getArg(0))) {
                        ClassLoader uiCl = result.getClass().getClassLoader();
                        api.info("homeux: MainFragment loaded by " + loaderTag(uiCl));
                        if (main2FunnelsDone.compareAndSet(false, true)) {
                            mainUiLoader = uiCl;
                            onMainUiLoader(uiCl);
                        }
                    }
                } catch (Throwable t) {
                    api.error("homeux: loader sniffer dispatch failed", t);
                }
                return result;
            }
        });
        api.info("homeux: loader sniffer hook ok");
    }

    /** 用真实运行时加载器重装全部 main2 漏斗（每项独立 try，互不拖垮）。 */
    private void onMainUiLoader(ClassLoader uiCl) {
        try {
            installTabListFilter(uiCl);
        } catch (Throwable t) {
            api.error("homeux: funnel(tab list) unavailable", t);
        }
        try {
            installHomeTabService(uiCl);
        } catch (Throwable t) {
            api.error("homeux: funnel(home tab service) unavailable", t);
        }
        try {
            installTabConfigFilter(uiCl);
        } catch (Throwable t) {
            api.error("homeux: funnel(tab config) unavailable", t);
        }
        try {
            installResourceManagerFilter(uiCl);
        } catch (Throwable t) {
            api.error("homeux: funnel(rm tab cache) unavailable", t);
        }
        try {
            installComposeContentProbe(uiCl);
        } catch (Throwable t) {
            api.error("homeux: compose probe (ui loader) unavailable", t);
        }
        // 「头像→我的」的路由动作派发不挂任何钩子：点击时按 VM 的加载器现解析现缓存。
        api.info("homeux: main2 funnels installed under " + loaderTag(uiCl));
    }

    // ===== 顶栏 overlay =====

    private void installTopBarOverlays() throws Throwable {
        final Class<?> barCls = api.load(cl, "tv.danmaku.bili.home.widget.top.HomeAppBarLayout");
        // HomeAppBarLayout 未覆写 onFinishInflate（纯继承 TintAppBarLayout），挂全部构造器：
        // inflate 时子 View 在 ctor 后加入，ctor 内 view.post() 的 RunQueue 会在
        // attach 后首次遍历执行——此时子树已就绪，decorate 时机确定性成立。
        java.lang.reflect.Constructor<?>[] ctors = barCls.getDeclaredConstructors();
        for (java.lang.reflect.Constructor<?> ctor : ctors) {
            ctor.setAccessible(true);
            api.addHookCtor("homeux: appbar ctor", ctor, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        final View bar = (View) chain.getThisObject();
                        if (bar != null && (api.isHomeTopbarMessageIcon() || api.isHomeAvatarMineEntry())) {
                            bar.post(new Runnable() {
                                @Override public void run() {
                                    try {
                                        decorate(bar);
                                    } catch (Throwable t) {
                                        api.error("homeux: decorate failed", t);
                                    }
                                }
                            });
                        }
                    } catch (Throwable t) {
                        api.error("homeux: decorate schedule failed", t);
                    }
                    return result;
                }
            });
        }
        api.info("homeux: appbar overlay hook ok, ctors=" + ctors.length);
    }

    /** 在顶栏容器上加：左侧头像点击层 + 右侧消息图标。 */
    private void decorate(View bar) {
        Activity act = resolveActivity(bar);
        if (act == null) {
            api.warn("homeux: no activity for appbar, skip decorate");
            return;
        }
        if (!(bar instanceof ViewGroup)) {
            return;
        }
        final ViewGroup barGroup = (ViewGroup) bar;
        if (barGroup.findViewWithTag("bili_tamer_top_overlay") != null) {
            return; // 已加过
        }
        float den = bar.getResources().getDisplayMetrics().density;
        final int dp = Math.max(1, Math.round(den));
        boolean avatarEntry = api.isHomeAvatarMineEntry();
        boolean msgIcon = api.isHomeTopbarMessageIcon();
        if (!avatarEntry && !msgIcon) {
            return;
        }

        // 容器：叠在顶栏内容行之上。
        //
        // v1.7.2 修复（分区栏错位）：旧做法把 overlay 追加到 HomeAppBarLayout（垂直
        // LinearLayout）末尾，再用负 topMargin=-childAt(0).height 把它「拉回」第一行。
        // 该负 margin 技巧只在 overlay 恰好是内容行的紧邻下一个兄弟时成立；服务端下发
        // 分区栏（推荐/动画）后兄弟布局流改变，负 margin 不再能把 overlay 精确拉回第一
        // 行，结果头像入口/消息图标掉到分区栏那一行（头像本体仍在顶栏）。
        //
        // 新做法：把内容行（childAt(0)）用一个 FrameLayout 包裹，overlay 作为该 wrapper
        // 的第二个子 View 与之同尺寸叠放。overlay 与内容行同处一个 FrameLayout，无论
        // 服务端在 HomeAppBarLayout 里再插多少行（分区栏等）都不影响叠放关系，恒精确
        // 覆盖顶栏内容行。wrapper 继承内容行原 LayoutParams（占位/高度不变，不撑高父容器）。
        final FrameLayout overlay = new FrameLayout(bar.getContext());
        overlay.setTag("bili_tamer_top_overlay");
        overlay.setClickable(false);

        View existingWrap = barGroup.findViewWithTag("bili_tamer_top_wrap");
        if (existingWrap instanceof FrameLayout) {
            // 防御：wrapper 已在（理论上不会，顶部已幂等拦截）——把 overlay 补进现有 wrapper。
            ((FrameLayout) existingWrap).addView(overlay, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            View contentRow = barGroup.getChildAt(0);
            if (contentRow == null) {
                api.warn("homeux: no content row (childAt(0)) in appbar, skip decorate");
                return;
            }
            final ViewGroup.LayoutParams contentLp = contentRow.getLayoutParams();
            final FrameLayout wrap = new FrameLayout(bar.getContext());
            wrap.setTag("bili_tamer_top_wrap");
            wrap.setClickable(false);
            barGroup.removeView(contentRow);
            barGroup.addView(wrap, 0, contentLp); // wrapper 继承内容行原占位（高度/边距不变）
            // 内容行放回 wrapper 时用 MATCH_PARENT 填满 wrapper（wrapper 高度=内容行原高度，
            // 故内容行视觉尺寸不变）；overlay 同尺寸叠在其上，恒精确覆盖内容行。
            ViewGroup.LayoutParams fill = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            wrap.addView(contentRow, fill);
            wrap.addView(overlay, fill);
            api.info("homeux: content row wrapped for stable overlay");
        }

        if (avatarEntry) {
            View avatar = new View(bar.getContext());
            avatar.setTag("bili_tamer_avatar_entry");
            avatar.setClickable(true);
            avatar.setContentDescription("我的");
            avatar.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    openMineEntry(v);
                }
            });
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    40 * dp, 40 * dp, Gravity.START | Gravity.CENTER_VERTICAL);
            lp.leftMargin = 4 * dp;
            overlay.addView(avatar, lp);
        }
        if (msgIcon) {
            // 信封 + 未读角标（红点带数字）合成按钮
            FrameLayout msgBtn = new FrameLayout(bar.getContext());
            msgBtn.setTag("bili_tamer_msg_entry");
            msgBtn.setClickable(true);
            msgBtn.setContentDescription("消息");
            msgBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    openRoute(v, "bilibili://im/compat/home");
                }
            });
            ImageView envelope = new ImageView(bar.getContext());
            envelope.setImageDrawable(new EnvelopeDrawable(Color.parseColor("#616161")));
            envelope.setScaleType(ImageView.ScaleType.CENTER);
            msgBtn.addView(envelope, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (api.isHomeTopbarMessageBadge()) {
                TextView badge = new TextView(bar.getContext());
                GradientDrawable badgeBg = new GradientDrawable();
                badgeBg.setColor(Color.RED);
                badgeBg.setCornerRadius(8 * den);
                badge.setBackground(badgeBg);
                badge.setTextColor(Color.WHITE);
                badge.setTextSize(9f);
                badge.setGravity(Gravity.CENTER);
                badge.setPadding(3 * dp, 0, 3 * dp, 0);
                badge.setMinimumWidth(14 * dp);
                badge.setVisibility(View.GONE);
                FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, 14 * dp, Gravity.END | Gravity.TOP);
                blp.rightMargin = 1 * dp;
                blp.topMargin = 3 * dp;
                msgBtn.addView(badge, blp);
                msgBadgeView = badge;
                startBadgePoller();
            }
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    40 * dp, 40 * dp, Gravity.END | Gravity.CENTER_VERTICAL);
            lp.rightMargin = 10 * dp;
            overlay.addView(msgBtn, lp);
        }
        api.info("homeux: topbar decorated avatar=" + avatarEntry + " msgIcon=" + msgIcon);
        bar.postDelayed(new Runnable() {
            @Override public void run() {
                if (api.isHomeTabbarRemoveMessage() || api.isHomeTabbarRemoveMine()) {
                    try {
                        applyTabRemoval(barGroup);
                    } catch (Throwable t) {
                        api.error("homeux: applyTabRemoval failed", t);
                    }
                }
            }
        }, 3000L);
    }

    private void openRoute(View v, String uri) {
        try {
            Activity act = resolveActivity(v);
            if (act == null) {
                return;
            }
            Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            it.setPackage(act.getPackageName());
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(it);
        } catch (Throwable t) {
            api.error("homeux: open route failed: " + uri, t);
        }
    }

    private Activity resolveActivity(View v) {
        try {
            android.content.Context c = v.getContext();
            while (c instanceof android.content.ContextWrapper) {
                if (c instanceof Activity) {
                    return (Activity) c;
                }
                c = ((android.content.ContextWrapper) c).getBaseContext();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ===== 顶栏头像 -> 「我的」完整页面 =====

    /**
     * 头像点击的五级兜底，按「离宿主原生行为越来越近」排：
     *  1) 真实 tab 选中派发（6.4.0/6.5.0 实机确证的动作总线；6.6.0 起底栏点击改走
     *     Compose 状态对象、总线上不再有这一发，解析不到就继续往下）。
     *  2) 宿主自己的「按路由切首页页」动作（{@link #dispatchMineRoute}）：国内版头像
     *     点击本体就是这一发，切的是**底栏**页选中态，**底栏画不画「我的」都照切** ——
     *     正是本模块把「我的」从底栏藏起来之后该走的路。
     *  3) 合成一次对底栏「我的」格的真实点击：**只在它确实被画出来的时候**，
     *     并且用渲染快照（renderedTabCount/renderedMineIndex）算格子，不用数据层槽位。
     *     数据层槽位 + 隐藏了的底栏 = 点到左边那一格（6.6.0 实机就是这样点进了动态页）。
     *  4) HomeTabServiceImpl 的 tab 点击事件分发（效果未确证，仅通知监听器）。
     *  5) 深链 {@code bilibili://user_center/mine}：打开 GeneralActivity 独立壳
     *     （实测缺底部功能区），只作最后兜底。
     */
    private void openMineEntry(View v) {
        String url = mineTabUrl != null ? mineTabUrl : "bilibili://user_center/mine";
        if (mineTabKept && dispatchMineTabSelect()) {
            return;
        }
        if (dispatchMineRoute(url)) {
            return;
        }
        int mineIdx = renderedMineIndex;
        int barCount = renderedTabCount;
        if (mineIdx >= 0 && barCount > 0
                && tapBottomTab(v, (mineIdx + 0.5f) / barCount)) {
            api.info("homeux: avatar -> synthesized tap on mine tab (rendered slot "
                    + mineIdx + "/" + barCount + ")");
            return;
        }
        if (barCount > 0) {
            api.debug("homeux: mine not on the drawn bar (" + barCount + " tab(s))"
                    + " -> skip synthesized tap");
        }
        // 次选：HomeTabServiceImpl 的 tab 点击事件分发（效果未确证，仅通知监听器）。
        Object svc = tabServiceRef.get();
        api.info("homeux: avatar clicked, url=" + url + ", service=" + (svc == null ? "null" : svc.getClass().getName()));
        if (svc != null) {
            try {
                for (Method mm : svc.getClass().getDeclaredMethods()) {
                    Class<?>[] ps = mm.getParameterTypes();
                    if (ps.length == 5 && ps[0] == boolean.class && ps[1] == int.class
                            && ps[2] == String.class && "android.view.View".equals(ps[3].getName())
                            && "android.os.Bundle".equals(ps[4].getName())
                            && Void.TYPE.equals(mm.getReturnType())) {
                        mm.setAccessible(true);
                        mm.invoke(svc, Boolean.TRUE, -1, url, null, null);
                        api.info("homeux: avatar -> tab service nav " + url);
                        return;
                    }
                }
                api.warn("homeux: tab service nav method not found on " + svc.getClass().getName());
            } catch (Throwable t) {
                api.error("homeux: tab service nav failed", t);
            }
        }
        openRoute(v, url);
    }

    /**
     * 投一发宿主自己的「按路由切首页页」动作，等价于宿主国内版点头像的那一行
     * （6.6.0 dex：TopLeftComponent 的头像 lambda 里
     * {@code homeFrameViewModel.v0(new bE1.g("bilibili://user_center/mine?bottom_tab_id=我的Bottom", 0, 2))}，
     * 国际版同一处被 {@code ew1.b.e()} 判成 oversea/intl 直接 "avatar click disabled for
     * oversea/intl, do nothing" —— 所以我们自己投，行为照抄）。
     *
     * url 必须带 query 参数：处理器（dispatchAction 的 g 分支）先
     * {@code h.b(url).i1("bottom_tab_id")} / {@code i1("bottom_tab_name")} 拿键，
     * 再拿它去底栏列表里逐项比内层 tab 数据的 h/b 字段；两个键都取不到就直接空转，
     * 页面一动不动。键值在数据钩子里从「我的」那一格现读（{@link #captureMineTabKeys}），
     * 读不到才退回宿主自己那串硬编码 {@code 我的Bottom}。
     *
     * 锚点缺失/解析失败/抛异常一律返回 false 继续往下兜底。
     */
    private boolean dispatchMineRoute(String url) {
        Object vm = khomeVmRef.get();
        if (vm == null || url == null) {
            return false;
        }
        Object[] hit = routeDispatch(vm.getClass());
        if (hit == null) {
            return false;
        }
        String key = mineRouteKey != null ? mineRouteKey : "bottom_tab_id=我的Bottom";
        String full = url + (url.indexOf('?') < 0 ? "?" : "&") + key;
        try {
            Method bus = (Method) hit[0];
            java.lang.reflect.Constructor<?> ctor = (java.lang.reflect.Constructor<?>) hit[1];
            bus.invoke(vm, ctor.newInstance(full, 0, 2));
            api.info("homeux: avatar -> host route action " + vm.getClass().getSimpleName()
                    + "." + bus.getName() + "(" + ((Class<?>) hit[2]).getName() + " \"" + full + "\")");
            return true;
        } catch (Throwable t) {
            api.error("homeux: route action dispatch failed: " + full, t);
            return false;
        }
    }

    /**
     * 解析「路由动作」这一发：{总线方法, (String,int,int) 构造器, 动作类}，解析一次缓存复用。
     * 候选表按版本 dex 里 {@code PageRouteComponent} 那个 Intent 处理口的 new-instance 逐版取来
     * （6.6.0=bE1.g→v0、6.5.0=jD1.g→w0、6.4.0=FC1.g→w0、6.3.0=FA1.g→x0，四处函数体逐条对读，
     * 三步形状「读 vm 字段 → new <包>.g(url,0,2) → vm.<总线>(g)」四版一致）。
     * 判定不靠包名猜：动作类必须有 (String,int,int) 构造器，且当前 VM 类上存在
     * 「单参 void、参数是接口、且该接口正好能接住这个动作类」的方法——两条同时成立才算认出总线。
     */
    private Object[] routeDispatch(Class<?> vmCls) {
        Object[] cached = routeDispatch;
        if (cached != null && routeDispatchFor == vmCls) {
            return cached;
        }
        ClassLoader loader = vmCls.getClassLoader();
        String[] candidates = {"bE1.g", "jD1.g", "FC1.g", "FA1.g"};
        for (int ci = 0; ci < candidates.length; ci++) {
            Class<?> actionCls;
            try {
                actionCls = api.load(loader, candidates[ci]);
            } catch (Throwable t) {
                continue;
            }
            java.lang.reflect.Constructor<?> ctor = null;
            try {
                ctor = actionCls.getConstructor(String.class, int.class, int.class);
            } catch (NoSuchMethodException ignored) {
            }
            if (ctor == null) {
                continue;
            }
            for (Method mm : vmCls.getDeclaredMethods()) {
                Class<?>[] ps = mm.getParameterTypes();
                if (ps.length == 1 && Void.TYPE.equals(mm.getReturnType())
                        && ps[0].isInterface() && ps[0].isAssignableFrom(actionCls)) {
                    mm.setAccessible(true);
                    ctor.setAccessible(true);
                    Object[] hit = new Object[]{mm, ctor, actionCls};
                    routeDispatchFor = vmCls;
                    routeDispatch = hit;
                    return hit;
                }
            }
        }
        api.warn("homeux: route action not resolvable (tried " + candidates[0] + ".."
                + candidates[candidates.length - 1] + " on " + vmCls.getName()
                + ") -> avatar falls back to synthesized tap / deep link");
        return null;
    }

    /**
     * 从底栏「我的」那一格读路由匹配键：内层 tab 数据对象（6.6.0 {@code fE1.k}）的
     * h=tab_id、b=name —— 字段字母序与 {@code LiveTabHooks} 的顶栏数据是同一套，四版同序。
     * 优先 tab_id（宿主自己用的就是它），空则退 name。
     */
    private void captureMineTabKeys(Object item) {
        if (item == null) {
            return;
        }
        try {
            for (Field f : item.getClass().getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        || f.getType().isPrimitive()
                        || f.getType() == String.class
                        || List.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                f.setAccessible(true);
                Object nested = f.get(item);
                if (nested == null) {
                    continue;
                }
                String name = readStringField(nested, "b");
                String tabId = readStringField(nested, "h");
                if ((name == null || name.length() == 0)
                        && (tabId == null || tabId.length() == 0)) {
                    continue;   // 不是 tab 数据对象，换下一个字段
                }
                String key = tabId != null && tabId.length() > 0
                        ? "bottom_tab_id=" + tabId : "bottom_tab_name=" + name;
                if (!key.equals(mineRouteKey)) {
                    mineRouteKey = key;
                    api.info("homeux: mine route key -> " + key);
                }
                return;
            }
        } catch (Throwable t) {
            api.debug("homeux: mine tab key read failed: " + t);
        }
    }

    private String readStringField(Object o, String name) {
        try {
            Field f = o.getClass().getDeclaredField(name);
            if (f.getType() != String.class) {
                return null;
            }
            f.setAccessible(true);
            return (String) f.get(o);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 真实派发：底栏 Compose 点击 handler（BottomTabComponent）把「选中第 i 个 tab」
     * 作为动作对象投进 HomeFrameViewModel 的单参接口方法（动作总线）。
     * 6.4.0 = w0(FC1.b) / FC1.c，6.5.0 = w0(jD1.b) / jD1.c——都在底栏点击 lambda 里
     * new 出来，实机确证过切页。总线方法名与动作类名一起漂移，且旧名会被别的语义
     * 复用：6.6.0 的 w0 已经变成 w0(dE1/h$a;)（底栏气泡/红点，由 bubble.a#b 构造，
     * 调用方只有 BottomTabComponent$onViewCreated$3），总线换成了 v0(bE1/b;)
     * （v0 里 new HomeFrameViewModel$dispatchAction$1 —— 真名自证）。
     * 6.6.0 结论：**不挂任何候选**。dex 全量 new-instance 扫描（52 个 bE1.* 动作类、
     * 53 处构造点）显示底栏点击 lambda 一个都不 new——它改的是 Compose 状态对象
     * （khome/widget/bottomtab/a#c(gE1.d,I) + I0/u#z()），点击链已经不走动作总线了。
     * 形状同构 ≠ 角色相同：bE1/c;{field I a, <init>(I)} 与 6.5.0 的 jD1/c; 逐条同形，
     * 但它在 6.6.0 只被 com.bilibili.search2.halfscreen.i 构造、被 PageRouteComponent
     * 消费后转成 bE1/g(String,I,I) 再投回总线——是「路由索引」不是「tab 索引」。
     * 所以只保留实机确证过的 6.4.0/6.5.0 候选；6.6.0 上这里必然解析失败（首次点击打
     * 一串 iface candidate 后记住这个事实，不再重扫/重刷），改走宿主路由动作
     * {@link #dispatchMineRoute}。
     */
    private boolean dispatchMineTabSelect() {
        try {
            Object vm = khomeVmRef.get();
            if (vm == null || tabDispatchMiss) {
                return false;   // 已确认这一版解析不出来：不重扫候选、不重复告警
            }
            Object[] hit = findTabDispatch(vm.getClass());
            if (hit != null) {
                Method mm = (Method) hit[0];
                java.lang.reflect.Constructor<?> intCtor = (java.lang.reflect.Constructor<?>) hit[1];
                String actionName = ((Class<?>) hit[2]).getName();
                Object action = intCtor.newInstance(mineSlotIndex);
                mm.invoke(vm, action);
                api.info("homeux: avatar -> real tab select dispatch " + mm.getName()
                        + "(slot " + mineSlotIndex + "/" + keptTabCount
                        + ", action=" + actionName + ")");
                return true;
            }
            tabDispatchMiss = true;
            if (tabDispatchWarned.compareAndSet(false, true)) {
                // 全候选失败：把 vm 上 void 单参接口方法的签名打出来（限 10 条）——
                // 下次漂移不用反编译，一行日志就能定位新接口组名。只打一次（每次点击都打会刷）。
                int printed = 0;
                for (Method mm : vm.getClass().getDeclaredMethods()) {
                    Class<?>[] ps = mm.getParameterTypes();
                    if (ps.length == 1 && Void.TYPE.equals(mm.getReturnType()) && ps[0].isInterface()) {
                        api.warn("khome: tab dispatch iface candidate " + mm.getName()
                                + "(" + ps[0].getName() + ")");
                        if (++printed >= 10) {
                            break;
                        }
                    }
                }
                api.warn("khome: tab select action class drift, tried jD1.c/FC1.c"
                        + " (6.6.0 起底栏点击不走动作总线，见 findTabDispatch 注释)");
            }
            return false;
        } catch (Throwable t) {
            api.error("homeux: real tab select dispatch failed", t);
            return false;
        }
    }

    /**
     * 只解析不派发：返回 {总线方法, (int) 构造器, 动作类}，找不到返回 null。
     * 判定即上面那三条形状规则，dispatchMineTabSelect 与启动期空跑共用一份逻辑，
     * 免得两处规则漂移。候选表只放实机确证过「点击 lambda new 它」的构建；
     * 新构建要么补上同样的 new-instance 取证，要么让它解析失败走兜底。
     */
    private Object[] findTabDispatch(Class<?> vmCls) {
        ClassLoader vmCl = vmCls.getClassLoader();
        String[] candidates = {"jD1.c", "FC1.c"};
        for (int ci = 0; ci < candidates.length; ci++) {
            Class<?> actionCls;
            try {
                actionCls = api.load(vmCl, candidates[ci]);
            } catch (Throwable t) {
                continue;
            }
            java.lang.reflect.Constructor<?> intCtor = null;
            try {
                intCtor = actionCls.getConstructor(int.class);
            } catch (NoSuchMethodException ignored) {
            }
            if (intCtor == null) {
                continue;
            }
            for (Method mm : vmCls.getDeclaredMethods()) {
                Class<?>[] ps = mm.getParameterTypes();
                if (ps.length == 1 && Void.TYPE.equals(mm.getReturnType())
                        && ps[0].isInterface() && ps[0].isAssignableFrom(actionCls)) {
                    mm.setAccessible(true);
                    intCtor.setAccessible(true);
                    return new Object[]{mm, intCtor, actionCls};
                }
            }
        }
        return null;
    }

    /**
     * 启动期一次性空跑：证明 6.6.0 的总线方法/动作类在当前构建里真能解析出来
     * （不 invoke——invoke 会当场跳到「我的」页）。点击时若解析已失败，
     * 日志里这一行就是「锚点没漂」的证词，漂了则直接给出 iface candidate 明细。
     */
    private void logTabDispatchAnchor(final Object vm) {
        if (vm == null || tabAnchorLogged) {
            return;
        }
        tabAnchorLogged = true;
        try {
            Object[] hit = findTabDispatch(vm.getClass());
            if (hit != null) {
                api.debug("homeux: tab dispatch anchor ready -> " + vm.getClass().getSimpleName()
                        + "." + ((Method) hit[0]).getName() + "("
                        + ((Method) hit[0]).getParameterTypes()[0].getName() + ")"
                        + " action=" + ((Class<?>) hit[2]).getName());
                return;
            }
            // 6.6.0 起底栏点击不走 tab 选中总线，这一发注定解析不出来；记下事实，
            // 首次点击就不必再扫一遍候选（那串 iface candidate 告警留给真漂移的场合），
            // 直接看路由动作那一条在不在（同样只解析不派发）。
            tabDispatchMiss = true;
            Object[] route = routeDispatch(vm.getClass());
            if (route == null) {
                api.debug("homeux: neither tab select nor route action resolvable on this build"
                        + " (avatar uses synthesized tap / tab service / deep link chain)");
                return;
            }
            api.debug("homeux: route dispatch anchor ready -> " + vm.getClass().getSimpleName()
                    + "." + ((Method) route[0]).getName() + "("
                    + ((Method) route[0]).getParameterTypes()[0].getName() + ")"
                    + " action=" + ((Class<?>) route[2]).getName());
        } catch (Throwable t) {
            api.debug("homeux: tab dispatch anchor probe failed: " + t);
        }
    }

    /**
     * 向底栏 tab_host（ComposeView）的指定槽位合成一次 DOWN+UP 触摸。
     * xFraction = 槽位中心在 bar 宽度里的比例。成功返回 true。
     */
    private boolean tapBottomTab(View v, float xFraction) {
        try {
            View root = v.getRootView();
            if (root == null) {
                return false;
            }
            View tabHost = root.findViewById(TAB_HOST_VIEW_ID);
            if (tabHost == null) {
                // id 随构建漂移：按名字解析兜底（资源名不变，值每版重排）。
                int resolved = v.getResources().getIdentifier("tab_host", "id",
                        v.getContext().getPackageName());
                if (resolved != View.NO_ID) {
                    tabHost = root.findViewById(resolved);
                }
            }
            if (tabHost == null || tabHost.getWidth() <= 0 || tabHost.getHeight() <= 0) {
                return false;
            }
            final float x = tabHost.getWidth() * xFraction;
            final float y = tabHost.getHeight() * 0.5f;
            long now = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
            tabHost.dispatchTouchEvent(down);
            down.recycle();
            final View target = tabHost;
            target.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        long t2 = SystemClock.uptimeMillis();
                        MotionEvent up = MotionEvent.obtain(t2, t2, MotionEvent.ACTION_UP, x, y, 0);
                        target.dispatchTouchEvent(up);
                        up.recycle();
                    } catch (Throwable ignored) {
                    }
                }
            }, 70L);
            return true;
        } catch (Throwable t) {
            api.error("homeux: synthesized tap failed", t);
            return false;
        }
    }
    /**
     * 底栏删 tab 的运行时落地(装机实测：tv.danmaku.bili.ui.main2.* 在插件化加载器里，
     * 主加载器里的同名类是死拷贝，直接 hook 全部静默)。从顶栏 overlay 出发：
     *  1) 遍历 Activity Fragment 树找到活的 MainFragment 实例 → 拿到真实加载器；
     *  2) 用真实加载器重装全部 main2 漏斗（供后续重建一致）；
     *  3) 直接过滤当前模型列表 BaseMainFrameFragment.a0；
     *  4) 强制置脏 MainResourceManager.c.c=true 并反射调 pm() → 整链立即重建
     *     （提供者 a() 已被 hook → 过滤生效；setTabs/pager/选中全链一致）。
     */
    private void applyTabRemoval(final ViewGroup bar) throws Exception {
        Activity act = resolveActivity(bar);
        if (act == null) {
            return;
        }
        ArrayList<Object> frags = new ArrayList<Object>();
        collectFragments(act, frags);
        Object mainFrag = null;
        StringBuilder fragNames = new StringBuilder();
        for (Object f : frags) {
            if (f == null) {
                continue;
            }
            fragNames.append(f.getClass().getName()).append(" | ");
            // 按 BaseMainFrameFragment 家族特征匹配（模型字段 a0），不依赖具体类名
            if (mainFrag == null && findDeclaredField(f.getClass(), "a0") != null) {
                mainFrag = f;
            }
        }
        if (mainFrag == null) {
            api.warn("homeux: main frame fragment not found in " + frags.size() + ": " + fragNames);
            return;
        }
        final ClassLoader uiCl = mainFrag.getClass().getClassLoader();
        api.info("homeux: live MainFragment loader=" + loaderTag(uiCl));
        if (main2FunnelsDone.compareAndSet(false, true)) {
            mainUiLoader = uiCl;
            onMainUiLoader(uiCl);
        }
        // 3) 直接过滤当前模型列表（字段 a0 在父类 BaseMainFrameFragment 上）
        boolean rmMsg = api.isHomeTabbarRemoveMessage();
        boolean rmMine = api.isHomeTabbarRemoveMine();
        // 按形状找模型列表字段：List 且首元素能解析出 bilibili:// 路由
        // （jadx 显示 f356121a0，真实名随构建漂移，形状稳定）
        List<?> model = null;
        Field modelField = null;
        for (Class<?> k = mainFrag.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field ff : k.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(ff.getModifiers())) {
                    continue;
                }
                try {
                    ff.setAccessible(true);
                    Object v = ff.get(mainFrag);
                    if (!(v instanceof List) || ((List<?>) v).isEmpty() || ((List<?>) v).size() > 8) {
                        continue;
                    }
                    Object first = ((List<?>) v).get(0);
                    if (first != null && pageUrlOf(first) != null) {
                        model = (List<?>) v;
                        modelField = ff;
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }
            if (model != null) {
                break;
            }
        }
        if (model == null) {
            // 6.5.0+ 底栏归 khome（"khome: filter armed on …" 那条才是现行路径），
            // 这条 main2 模型列表只服务 6.3.0/6.4.0，miss 属预期，不打 ERROR。
            api.debug("homeux: tab model list field not found on "
                    + mainFrag.getClass().getName() + " (legacy main2 path; khome covers 6.5.0+)");
            return;
        }
        api.info("homeux: tab model list field=" + modelField.getName() + " size=" + model.size());
        int removedMsg = 0;
        int removedMine = 0;
        int keptIdx = 0;
        int mineIdx = -1;
        int keptCount = 0;
        Iterator<?> it = model.iterator();
        while (it.hasNext()) {
            Object item = it.next();
            String url = pageUrlOf(item);
            boolean drop = false;
            if (url != null) {
                if (rmMsg && startsWithAny(url, TAB_URL_REMOVE_MESSAGE)) {
                    drop = true;
                    removedMsg++;
                } else if (rmMine && startsWithAny(url, TAB_URL_REMOVE_MINE)) {
                    mineTabUrl = url;
                    drop = true;
                    removedMine++;
                }
            }
            if (drop) {
                it.remove();
            } else {
                if (url != null && startsWithAny(url, TAB_URL_REMOVE_MINE)) {
                    mineIdx = keptIdx;
                }
                keptIdx++;
                keptCount++;
            }
        }
        api.info("homeux: model list filtered -" + removedMsg + "msg -" + removedMine
                + "mine, kept=" + keptCount);
        if (keptCount > 0) {
            keptTabCount = keptCount;
            if (mineIdx >= 0) {
                mineSlotIndex = mineIdx;
                mineTabKept = true;
            } else {
                mineTabKept = false;
            }
        }
        if (removedMsg + removedMine == 0) {
            return; // 无可删项，不必重建
        }
        // 4) 强制置脏 + 调 pm() 重建底栏与 pager
        Class<?> mgrCls = api.load(uiCl, "tv.danmaku.bili.ui.main2.resource.MainResourceManager");
        Field qF = mgrCls.getDeclaredField("q");
        qF.setAccessible(true);
        Object mgr = qF.get(null);
        if (mgr != null) {
            Field cF = findDeclaredField(mgr.getClass(), "c");
            if (cF != null) {
                cF.setAccessible(true);
                Object wrapper = cF.get(mgr);
                if (wrapper != null) {
                    Field dirty = findDeclaredField(wrapper.getClass(), "c");
                    if (dirty != null && dirty.getType() == boolean.class) {
                        dirty.setAccessible(true);
                        dirty.setBoolean(wrapper, true);
                    }
                }
            }
        }
        Method pm = null;
        for (Class<?> k = mainFrag.getClass(); k != null && pm == null; k = k.getSuperclass()) {
            try {
                pm = k.getDeclaredMethod("pm");
            } catch (NoSuchMethodException ignored) {
            }
        }
        if (pm == null) {
            api.error("homeux: BaseMainFrameFragment.pm() not found", null);
            return;
        }
        pm.setAccessible(true);
        pm.invoke(mainFrag);
        api.info("homeux: pm() invoked - bottom bar rebuilt");
    }

    /** 递归收集 Activity 里所有 Fragment（含子 FragmentManager）。 */
    private void collectFragments(Object owner, ArrayList<Object> out) {
        try {
            Object fm;
            if (owner instanceof Activity) {
                fm = ((Activity) owner).getClass().getMethod("getSupportFragmentManager").invoke(owner);
            } else {
                fm = owner.getClass().getMethod("getChildFragmentManager").invoke(owner);
            }
            Object list = fm.getClass().getMethod("getFragments").invoke(fm);
            if (list instanceof List) {
                for (Object f : (List<?>) list) {
                    if (f == null) {
                        continue;
                    }
                    out.add(f);
                    collectFragments(f, out);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 沿类层链找声明字段（含父类）。 */
    private Field findDeclaredField(Class<?> k, String name) {
        for (; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                return k.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    // ===== HomeTabServiceImpl（9100300 Compose 底栏 tab 管道）=====

    /**
     * 类名不带混淆（resource 包保留真名，跨构建稳）。做两件事：
     *  1) 构造器 hook 捕获实例（头像导航用）；
     *  2) hook「无参返回 java.util.List」的方法（g()/k()，Compose 底栏的 tab 列表
     *     源头：g() 实时读 CachedResourceResolver 配置，k() 读 tryUpdateHomeTab 缓存），
     *     AFTER 过滤「消息」/「我的」——底栏点击按 url 派发（Yf0.n.a 带 url），
     *     不依赖列表索引，无错位问题。
     */
    private void installHomeTabService(ClassLoader uiCl) throws Throwable {
        Class<?> impl = api.load(uiCl, "tv.danmaku.bili.ui.main2.resource.HomeTabServiceImpl");
        for (final java.lang.reflect.Constructor<?> ctor : impl.getDeclaredConstructors()) {
            ctor.setAccessible(true);
            api.addHookCtor("homeux: tab service ctor", ctor, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        tabServiceRef.set(chain.getThisObject());
                    } catch (Throwable ignored) {
                    }
                    return result;
                }
            });
        }
        int hooked = 0;
        for (Method mm : impl.getDeclaredMethods()) {
            if (mm.getParameterTypes().length != 0
                    || !"java.util.List".equals(mm.getReturnType().getName())) {
                continue;
            }
            api.deoptimize(mm);
            api.addHook("homeux: home tab list " + mm.getName(), mm, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        result = filterTabList(result);
                    } catch (Throwable t) {
                        api.error("homeux: filter home tab list failed", t);
                    }
                    return result;
                }
            });
            hooked++;
        }
        api.info("homeux: home tab service hook ok, list methods=" + hooked);
    }

    // ===== 底栏 tab 配置过滤（9100300 主漏斗）=====

    private final AtomicBoolean configProbe = new AtomicBoolean(false);

    /**
     * 所有 tab 消费者的数据源头：CachedResourceResolver.a() 返回缓存 TabResponse
     * （tabData.tab = List<MainResourceManager.Tab>，磁盘 home_tab_v2.data 解析产物）。
     * AFTER 原地移除「消息」/「我的」——对象是全局缓存的单一实例，改一次全链生效
     * （底栏渲染、pager、角标计数一致地"看不到"被删 tab，等同服务端没下发）。
     * Tab 真实字段（9100300）：b=name c=url e=id。
     */
    private void installTabConfigFilter(ClassLoader uiCl) throws Throwable {
        Class<?> resolver = api.load(uiCl, "tv.danmaku.bili.ui.main2.resource.CachedResourceResolver");
        int hooked = 0;
        for (Method mm : resolver.getDeclaredMethods()) {
            if (mm.getParameterTypes().length != 0
                    || !mm.getReturnType().getName().endsWith("MainResourceManager$TabResponse")) {
                continue;
            }
            api.deoptimize(mm);
            api.addHook("homeux: tab config " + mm.getName(), mm, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object resp = chain.proceed();
                    try {
                        filterTabResponse(resp);
                    } catch (Throwable t) {
                        api.error("homeux: filter tab config failed", t);
                    }
                    return resp;
                }
            });
            hooked++;
        }
        if (hooked == 0) {
            api.error("homeux: CachedResourceResolver no-arg TabResponse method not found", null);
            return;
        }
        api.info("homeux: tab config filter hook ok, methods=" + hooked);
    }

    private void filterTabResponse(Object resp) throws Exception {
        if (resp == null) {
            return;
        }
        boolean rmMsg = api.isHomeTabbarRemoveMessage();
        boolean rmMine = api.isHomeTabbarRemoveMine();
        Field tdF = resp.getClass().getDeclaredField("tabData");
        tdF.setAccessible(true);
        Object td = tdF.get(resp);
        if (td == null) {
            return;
        }
        Field tabF = td.getClass().getDeclaredField("tab");
        tabF.setAccessible(true);
        Object tabObj = tabF.get(td);
        if (!(tabObj instanceof List)) {
            return;
        }
        List<?> tabs = (List<?>) tabObj;
        boolean probe = configProbe.compareAndSet(false, true);
        if (!rmMsg && !rmMine) {
            return;
        }
        int removedMsg = 0;
        int removedMine = 0;
        StringBuilder sb = probe ? new StringBuilder("homeux: config tabs[") : null;
        Iterator<?> it = tabs.iterator();
        while (it.hasNext()) {
            Object tab = it.next();
            String url = pageUrlOf(tab);
            if (probe && sb != null) {
                sb.append(url).append(",");
            }
            if (url == null) {
                continue;
            }
            if (rmMsg && startsWithAny(url, TAB_URL_REMOVE_MESSAGE)) {
                it.remove();
                removedMsg++;
            } else if (rmMine && startsWithAny(url, TAB_URL_REMOVE_MINE)) {
                mineTabUrl = url;
                it.remove();
                removedMine++;
            }
        }
        if (probe && sb != null) {
            sb.append("] rmMsg=").append(removedMsg).append(" rmMine=").append(removedMine);
            api.info(sb.toString());
        }
        if (removedMsg + removedMine > 0) {
            api.info("homeux: tab config filtered -" + removedMsg + "msg -" + removedMine + "mine");
        }
    }

    // ===== 底栏 tab 缓存过滤（9100300 实测唯一写入点）=====

    /**
     * MainResourceManager.h(boolean,boolean)：底栏 tab 缓存（静态单例字段 d →
     * a 值 = List<MainResourceManager.Tab>，Tab 真实字段 b=name c=url e=id）的
     * 唯一赋值点（磁盘 C/k/C35588a 三来源）。AFTER 原地移除「消息」/「我的」，
     * 并记录「我的」在过滤后 bar 中的槽位（顶栏头像合成点击定位用）。
     */
    private void installResourceManagerFilter(ClassLoader uiCl) throws Throwable {
        Class<?> mgrCls = api.load(uiCl, "tv.danmaku.bili.ui.main2.resource.MainResourceManager");
        Method h = null;
        for (Method mm : mgrCls.getDeclaredMethods()) {
            Class<?>[] ps = mm.getParameterTypes();
            if (ps.length == 2 && ps[0] == boolean.class && ps[1] == boolean.class
                    && Void.TYPE.equals(mm.getReturnType())) {
                h = mm;
                break;
            }
        }
        if (h == null) {
            api.error("homeux: MainResourceManager.h(ZZ) not found", null);
            return;
        }
        api.deoptimize(h);
        api.addHook("homeux: rm tab cache h", h, new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                try {
                    filterResourceManagerTabs(chain.getThisObject());
                } catch (Throwable t) {
                    api.error("homeux: filter rm tabs failed", t);
                }
                return result;
            }
        });
        api.info("homeux: rm tab cache filter hook ok");
    }

    private void filterResourceManagerTabs(Object mgr) throws Exception {
        if (mgr == null) {
            return;
        }
        boolean rmMsg = api.isHomeTabbarRemoveMessage();
        boolean rmMine = api.isHomeTabbarRemoveMine();
        Field dF = mgr.getClass().getDeclaredField("d");
        dF.setAccessible(true);
        Object wrapper = dF.get(mgr);
        if (wrapper == null) {
            return;
        }
        Field aF = wrapper.getClass().getDeclaredField("a");
        aF.setAccessible(true);
        Object listObj = aF.get(wrapper);
        if (!(listObj instanceof List)) {
            return;
        }
        List<?> tabs = (List<?>) listObj;
        boolean probe = configProbe.compareAndSet(false, true);
        if (!rmMsg && !rmMine) {
            // 不过滤也要记录「我的」槽位（默认全保留）
            return;
        }
        int removedMsg = 0;
        int removedMine = 0;
        int keptIdx = 0;
        int mineIdx = -1;
        int keptCount = 0;
        StringBuilder sb = probe ? new StringBuilder("homeux: rm tabs[") : null;
        Iterator<?> it = tabs.iterator();
        while (it.hasNext()) {
            Object tab = it.next();
            String url = pageUrlOf(tab);
            if (probe && sb != null) {
                sb.append(url).append(",");
            }
            boolean drop = false;
            if (url != null) {
                if (rmMsg && startsWithAny(url, TAB_URL_REMOVE_MESSAGE)) {
                    drop = true;
                    removedMsg++;
                } else if (rmMine && startsWithAny(url, TAB_URL_REMOVE_MINE)) {
                    mineTabUrl = url;
                    drop = true;
                    removedMine++;
                }
            }
            if (drop) {
                it.remove();
            } else {
                if (url != null && startsWithAny(url, TAB_URL_REMOVE_MINE)) {
                    mineIdx = keptIdx;
                }
                keptIdx++;
                keptCount++;
            }
        }
        if (probe && sb != null) {
            sb.append("] rmMsg=").append(removedMsg).append(" rmMine=").append(removedMine);
            api.info(sb.toString());
        }
        if (removedMsg + removedMine > 0) {
            api.info("homeux: rm tab cache filtered -" + removedMsg + "msg -" + removedMine
                    + "mine, kept=" + keptCount);
        }
        if (keptCount > 0) {
            keptTabCount = keptCount;
            if (mineIdx >= 0) {
                mineSlotIndex = mineIdx;
                mineTabKept = true;
            } else {
                mineTabKept = false;
            }
        }
    }

    // ===== Compose content 探针（Pegasus 底栏专项 RE，v1.7.0）=====

    /**
     * ComposeView.setContent(Function2) 是 Compose 自家公开 API（AXML 里 inflate 的
     * View 类名不能混淆），content lambda 的实现类名 = 宿主类$函数名$N，直接暴露
     * 底栏 composable 身份。按名尝试 androidx 两个候选类；真名以 composeTruthWalk
     * 的设备实测为准（androidx 内部类可能被重命名，但 ComposeView 本体必真名）。
     */
    private void installComposeContentProbe(ClassLoader loader) {
        if (loader == null || !composeProbedLoaders.add(loader)) {
            return;
        }
        String[] candidates = {
                "androidx.compose.ui.platform.ComposeView",
                "androidx.compose.ui.platform.AbstractComposeView",
        };
        int hooked = 0;
        for (String name : candidates) {
            try {
                hooked += hookSetContent(api.load(loader, name));
            } catch (Throwable t) {
                // 主加载器上没有 androidx 是预期 miss（插件加载器那一轮才挂得上），
                // 只作诊断；ClassLoader.toString() 会把整条 dexpath 打出来，别贴它。
                api.debug("compose: " + name + " absent from " + loaderTag(loader)
                        + " (" + t.getClass().getSimpleName() + ")");
            }
        }
        api.info("compose: probe install ok, hooked=" + hooked + " loader=" + loaderTag(loader));
    }

    /** 加载器的短标识：够区分主/插件加载器，又不把整条 dexpath 打进日志。 */
    private static String loaderTag(ClassLoader loader) {
        if (loader == null) {
            return "null";
        }
        return loader.getClass().getSimpleName() + "@"
                + Integer.toHexString(System.identityHashCode(loader));
    }

    /** 挂类上全部 setContent(单参 Function2)（含子类覆写），返回挂上数。 */
    private int hookSetContent(Class<?> cls) {
        int hooked = 0;
        for (Method mm : cls.getDeclaredMethods()) {
            if (!"setContent".equals(mm.getName()) || mm.getParameterTypes().length != 1) {
                continue;
            }
            String p = mm.getParameterTypes()[0].getName();
            if (!p.endsWith("Function2")) {
                api.info("compose: skip " + cls.getName() + ".setContent(" + p + ")");
                continue;
            }
            String key = System.identityHashCode(mm.getDeclaringClass().getClassLoader())
                    + "#" + mm.toString();
            if (!composeHooked.add(key)) {
                continue;
            }
            try {
                api.deoptimize(mm);
                api.addHook("compose: " + cls.getName() + ".setContent", mm, new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Object result = chain.proceed();
                        logComposeContent(chain.getThisObject(), chain.getArg(0));
                        return result;
                    }
                });
                hooked++;
                api.info("compose: hooked " + cls.getName() + ".setContent(" + p + ")");
            } catch (Throwable t) {
                api.error("compose: hook " + cls.getName() + ".setContent failed", t);
            }
        }
        return hooked;
    }

/**
 * 打 content lambda 真实实现类名。setContent 收到的常是 ComposableLambdaImpl
 * （composeLambda 记忆化包装），真身份在其内部 Function2 字段（block）的实现类——
 * 递归解包最多 3 层。首次出现时用 new Throwable() 抓全调用栈（hook 线程内
 * Thread.currentThread().getStackTrace() 在 LSPosed 下会截短）。
 */
    private void logComposeContent(Object viewObj, Object lambda) {
        try {
            String viewCls = viewObj == null ? "null" : viewObj.getClass().getName();
            int vid = viewObj instanceof View ? ((View) viewObj).getId() : View.NO_ID;
            boolean tabHost = vid == TAB_HOST_VIEW_ID;
            Object real = lambda;
            int depth = 0;
            while (depth < 3) {
                Object inner = unwrapFunction2(real);
                if (inner == null || inner == real) {
                    break;
                }
                real = inner;
                depth++;
            }
            String lambdaCls = lambda == null ? "null" : lambda.getClass().getName();
            String realCls = real == null ? "null" : real.getClass().getName();
            String key = lambdaCls + "|" + viewCls + "|" + realCls;
            boolean first = composeLogged.add(key);
            if (!first && !tabHost) {
                return;
            }
            String stackKey = (tabHost ? "tabhost|" : "") + realCls;
            boolean stackFirst = tabHost ? composeLogged.add(stackKey) : first;
            Object parent = viewObj instanceof View ? ((View) viewObj).getParent() : null;
            api.info("compose: setContent view=" + viewCls + " id=0x" + Integer.toHexString(vid)
                    + (tabHost ? " [TAB_HOST]" : "")
                    + " parent=" + (parent == null ? "null" : parent.getClass().getName())
                    + " wrapper=" + lambdaCls + " real=" + realCls + " depth=" + depth
                    + " loader=" + shortLoader(real));
            if (depth == 0 && lambda != null && tabHost) {
                StringBuilder fds = new StringBuilder("compose: wrapper fields[");
                for (Class<?> k = lambda.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                    for (Field ff : k.getDeclaredFields()) {
                        fds.append(k.getSimpleName()).append(".").append(ff.getName())
                           .append(":").append(ff.getType().getName()).append(" ");
                    }
                }
                api.info(fds.append("]").toString());
            }
            if (stackFirst) {
                StackTraceElement[] st = new Throwable().getStackTrace();
                StringBuilder sb = new StringBuilder("compose: stack for ").append(realCls).append(":");
                int kept = 0;
                for (StackTraceElement e : st) {
                    String c = e.getClassName();
                    if (c.startsWith("java.lang.Thread") || c.startsWith("com.tamer.bili")
                            || "java.lang.reflect.Method".equals(c)) {
                        continue;
                    }
                    sb.append("\n  at ").append(c).append(".").append(e.getMethodName());
                    if (++kept >= 40) {
                        break;
                    }
                }
                api.info(sb.toString());
            }
            if (tabHost && viewObj != null && composeTruthDone.compareAndSet(false, true)) {
                StringBuilder chain = new StringBuilder("compose: tab_host class chain:");
                for (Class<?> k = viewObj.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                    chain.append("\n  ").append(k.getName());
                }
                api.info(chain.toString());
            }
        } catch (Throwable t) {
            api.error("compose: log failed", t);
        }
    }

    /**
     * 在对象（沿类链）上找第一个「值实现 Function2 接口」的字段读出实例
     * （ComposableLambdaImpl.block；声明类型可能被 R8 合并改型，按值形态判）。
     */
    private Object unwrapFunction2(Object o) {
        if (o == null) {
            return null;
        }
        try {
            for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    f.setAccessible(true);
                    Object v = f.get(o);
                    if (v != null && v != o && implementsFunction2(v.getClass())) {
                        return v;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 值的类链（含接口）上是否有 *Function2。 */
    private boolean implementsFunction2(Class<?> c) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            if (k.getName().endsWith("Function2")) {
                return true;
            }
            for (Class<?> i : k.getInterfaces()) {
                if (i.getName().endsWith("Function2")) {
                    return true;
                }
                for (Class<?> i2 : i.getInterfaces()) {
                    if (i2.getName().endsWith("Function2")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** loader 缩写（避免整段 PathClassLoader 字符串刷屏）。 */
    private String shortLoader(Object o) {
        if (o == null) {
            return "null";
        }
        ClassLoader l = o.getClass().getClassLoader();
        return l == null ? "bootstrap"
                : l.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(l));
    }

    // ===== khome 底栏 tab 模型探针/过滤（v2，形状锚定）=====

    /**
     * 6.4.0 底栏=tv.danmaku.bili.home.components.bottomtab.BottomTabComponent（真名），
     * tab 列表源=HomeFrameViewModel（真名）root StateFlow 的 DC1.a.c.a=List<KC1.d>
     * （混淆名随构建漂移）。做法全形状锚定：
     *  1) hook 真名类 HomeFrameViewModel 构造器拿实例；
     *  2) 轮询其 StateFlowImpl 字段 getValue() 的 root 对象；
     *  3) root→字段→size1..8 的 List、元素含 String 字段+boolean 字段 → 锁定
     *     页面状态类/列表字段/元素类；首次打全量明细（设备真值校准过滤词）；
     *  4) hook 页面状态类全部构造器 AFTER，把列表字段换成按 tab 路由名过滤的副本
     *     （构造器返回前改字段，发布前无观察者；底栏/pager/角标同源全一致）。
     */
    private void installKhomeTabProbe(ClassLoader loader) throws Throwable {
        if (loader == null) {
            return;
        }
        Class<?> vmCls = api.load(loader, "tv.danmaku.bili.khome.vm.HomeFrameViewModel");
        for (final java.lang.reflect.Constructor<?> ctor : vmCls.getDeclaredConstructors()) {
            ctor.setAccessible(true);
            api.addHookCtor("khome: frame vm ctor", ctor, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        if (khomeVmRef.compareAndSet(null, result)) {
                            api.info("khome: HomeFrameViewModel captured " + result.getClass().getName());
                            logTabDispatchAnchor(result);
                            scheduleKhomeDiscovery();
                        }
                    } catch (Throwable t) {
                        api.error("khome: vm capture failed", t);
                    }
                    return result;
                }
            });
        }
        api.info("khome: probe hooks ok, ctors=" + vmCls.getDeclaredConstructors().length);
    }

    private void scheduleKhomeDiscovery() {
        uiHandler.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    if (!discoverKhomeTabModel()) {
                        khomeProbeAttempts++;
                        if (khomeProbeAttempts <= 15) {
                            uiHandler.postDelayed(this, 2000L);
                        } else {
                            api.warn("khome: tab model discovery gave up after " + khomeProbeAttempts + " attempts");
                        }
                    }
                } catch (Throwable t) {
                    api.error("khome: discovery failed", t);
                }
            }
        }, 2500L);
    }

    /** true=发现并武装过滤；false=数据未就绪（继续轮询）。 */
    private boolean discoverKhomeTabModel() throws Exception {
        if (khomeFilterArmed.get()) {
            return true;
        }
        Object vm = khomeVmRef.get();
        if (vm == null) {
            return false;
        }
        Object root = null;
        for (Field f : vm.getClass().getDeclaredFields()) {
            if (!f.getType().getName().endsWith("StateFlowImpl")) {
                continue;
            }
            f.setAccessible(true);
            Object flow = f.get(vm);
            if (flow != null) {
                root = flow.getClass().getMethod("getValue").invoke(flow);
            }
            break;
        }
        if (root == null) {
            return false;
        }
        if (!root.getClass().getName().equals(String.valueOf(khomeRootClsName()))) {
            khomeRootClsName(root.getClass().getName());
            api.info("khome: root state class=" + root.getClass().getName());
        }
        // root→子对象→全部 List(1..8) 候选：元素有 String 字段。底栏元素是包装类
        // （KC1.d：13 个 boolean 选中/标志位），顶栏元素直接是 JC1.n（1 个 boolean）
        // —— 按「元素 boolean 字段数」打分取最高，避免选成顶栏列表。
        Class<?> bestChildCls = null;
        Field bestListField = null;
        Field bestNameField = null;
        List<?> bestList = null;
        int bestScore = -1;
        StringBuilder cands = new StringBuilder();
        for (Field rf : root.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(rf.getModifiers())) {
                continue;
            }
            rf.setAccessible(true);
            Object child = rf.get(root);
            if (child == null || isSimple(child)) {
                continue;
            }
            for (Field cf : child.getClass().getDeclaredFields()) {
                if (!List.class.isAssignableFrom(cf.getType())) {
                    continue;
                }
                cf.setAccessible(true);
                Object lv = cf.get(child);
                if (!(lv instanceof List)) {
                    continue;
                }
                List<?> list = (List<?>) lv;
                if (list.isEmpty() || list.size() > 8) {
                    continue;
                }
                Object first = list.get(0);
                if (first == null) {
                    continue;
                }
                Field nameF = findStringField(first.getClass());
                if (nameF == null) {
                    continue;
                }
                int bools = 0;
                for (Field bf : first.getClass().getDeclaredFields()) {
                    if (bf.getType() == boolean.class
                            && !java.lang.reflect.Modifier.isStatic(bf.getModifiers())) {
                        bools++;
                    }
                }
                int score = bools * 10 + first.getClass().getDeclaredFields().length;
                cands.append("\n  cand root.").append(rf.getName()).append(".")
                        .append(cf.getName()).append(" size=").append(list.size())
                        .append(" item=").append(first.getClass().getName())
                        .append(" bools=").append(bools).append(" score=").append(score);
                if (score > bestScore) {
                    bestScore = score;
                    bestChildCls = child.getClass();
                    bestListField = cf;
                    bestNameField = nameF;
                    bestList = list;
                }
            }
        }
        if (bestChildCls == null || bestScore < 20) {
            api.warn("khome: no bottom-tab-like list yet (need bools>=2), candidates:"
                    + (cands.length() == 0 ? " none" : cands.toString()));
            return false;
        }
        khomePageStateCls = bestChildCls;
        khomeTabListField = bestListField;
        khomeTabItemCls = bestList.get(0).getClass();
        khomeItemNameField = bestNameField;
        api.info("khome: candidates:" + cands);
        logTabModelDetails(root, bestChildCls, bestListField, bestList, bestNameField);
        armKhomeFilter();
        return true;
    }

    private String khomeRootClsName;

    private String khomeRootClsName() {
        return khomeRootClsName;
    }

    private void khomeRootClsName(String v) {
        khomeRootClsName = v;
    }

    private boolean isSimple(Object o) {
        return o instanceof String || o instanceof Number || o instanceof Boolean
                || o instanceof Character || o instanceof List;
    }

    /** 元素类上找 String 字段（KC1.d.b=tab 路由名；兜底取唯一 String 实例字段）。 */
    private Field findStringField(Class<?> itemCls) {
        Field named = null;
        try {
            named = itemCls.getDeclaredField("b");
            if (named.getType() == String.class) {
                named.setAccessible(true);
                return named;
            }
        } catch (NoSuchFieldException ignored) {
        }
        for (Field f : itemCls.getDeclaredFields()) {
            if (f.getType() == String.class
                    && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                f.setAccessible(true);
                return f;
            }
        }
        return null;
    }

    /** 首次打全量 tab 明细（每元素的 String 名 + boolean 字段），校准过滤词。 */
    private void logTabModelDetails(Object root, Class<?> childCls, Field cf, List<?> list, Field nameF) {
        try {
            StringBuilder sb = new StringBuilder("khome: tab model found root=")
                    .append(root.getClass().getName())
                    .append(" state=").append(childCls.getName())
                    .append(".").append(cf.getName())
                    .append(" item=").append(list.get(0).getClass().getName())
                    .append(" nameField=").append(nameF.getName())
                    .append(" size=").append(list.size())
                    .append(" items[");
            for (int i = 0; i < list.size(); i++) {
                Object it = list.get(i);
                sb.append("\n  [").append(i).append("] ").append(it.getClass().getSimpleName()).append(":");
                for (Field f : it.getClass().getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    f.setAccessible(true);
                    Object v = f.get(it);
                    if (v instanceof String || v instanceof Boolean || v instanceof Number) {
                        sb.append(" ").append(f.getName()).append("=").append(v);
                    } else if (v != null && !isSimple(v)) {
                        // 一层嵌套：tab 信息对象（JC1.n 形状）里的 String 字段
                        StringBuilder inner = new StringBuilder();
                        for (Field f2 : v.getClass().getDeclaredFields()) {
                            if (f2.getType() != String.class
                                    || java.lang.reflect.Modifier.isStatic(f2.getModifiers())) {
                                continue;
                            }
                            f2.setAccessible(true);
                            Object v2 = f2.get(v);
                            if (v2 != null && ((String) v2).length() > 0) {
                                inner.append(" ").append(f2.getName()).append("=").append(v2);
                            }
                        }
                        if (inner.length() > 0) {
                            sb.append(" ").append(f.getName()).append("{").append(inner).append(" }");
                        }
                    }
                }
            }
            api.info(sb.append(" ]").toString());
        } catch (Throwable t) {
            api.error("khome: tab detail log failed", t);
        }
    }

    /** 武装：hook 页面状态类全部构造器 AFTER 过滤列表字段 + 底栏渲染隐藏「我的」。 */
    private void armKhomeFilter() {
        if (!khomeFilterArmed.compareAndSet(false, true)) {
            return;
        }
        try {
            installBottomBarRenderFilter(khomePageStateCls.getClassLoader());
        } catch (Throwable t) {
            api.error("khome: render filter unavailable", t);
        }
        final Class<?> stateCls = khomePageStateCls;
        int hooked = 0;
        for (final java.lang.reflect.Constructor<?> ctor : stateCls.getDeclaredConstructors()) {
            try {
                ctor.setAccessible(true);
                api.addHookCtor("khome: tab state ctor", ctor, new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Object result = chain.proceed();
                        try {
                            filterKhomeTabList(result);
                        } catch (Throwable t) {
                            api.error("khome: filter failed", t);
                        }
                        return result;
                    }
                });
                hooked++;
            } catch (Throwable t) {
                api.error("khome: hook state ctor failed", t);
            }
        }
        api.info("khome: filter armed on " + stateCls.getName() + ", ctors=" + hooked);
    }

    /** 按 KC1.d.b（tab 路由名）过滤底栏列表；命中替换字段为新 List。 */
    private void filterKhomeTabList(Object state) throws Exception {
        Field lf = khomeTabListField;
        Field nf = khomeItemNameField;
        if (lf == null || nf == null || state == null
                || !khomePageStateCls.isInstance(state)) {
            return;
        }
        Object lv = lf.get(state);
        if (!(lv instanceof List) || ((List<?>) lv).isEmpty()) {
            return;
        }
        boolean rmMsg = api.isHomeTabbarRemoveMessage();
        // 注意：「我的」不做数据级删除（删数据会连 pager 页一起丢，头像真实派发就没了），
        // 它在底栏渲染参数处隐藏（见 installBottomBarRenderFilter）。
        List<?> list = (List<?>) lv;
        ArrayList<Object> kept = new ArrayList<Object>();
        int rmMsgN = 0;
        int mineIdx = -1;
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            Object rawName = nf.get(item);
            String name = rawName instanceof String ? ((String) rawName).toLowerCase() : "";
            boolean dropMsg = rmMsg && (name.contains("im") || name.contains("message") || name.contains("msg"));
            if (dropMsg) {
                rmMsgN++;
                continue;
            }
            if (name.contains("mine") || name.contains("user_center")) {
                mineIdx = kept.size();
                if (rawName instanceof String) {
                    // 宿主下发的「我的」真实路由，头像派发/深链都用它（比硬编码兜底准）。
                    mineTabUrl = (String) rawName;
                }
                // 路由动作的匹配键（内层 tab 数据的 tab_id/name）只能从这一格上现读。
                captureMineTabKeys(item);
            }
            kept.add(item);
        }
        if (rmMsgN > 0) {
            api.info("khome: bottom tab data filtered " + list.size() + "->" + kept.size()
                    + " droppedMsg=" + rmMsgN);
            lf.set(state, kept);
        }
        if (kept.size() > 0 && mineIdx >= 0) {
            keptTabCount = kept.size();
            mineSlotIndex = mineIdx;
            mineTabKept = true;
            // 这里**不写**渲染快照：底栏画几格、「我的」在不在里面，只有渲染钩子知道。
            // 数据状态每轮都会重建，在这里抹掉「已隐藏」就等于把点错格子的事故放回去。
        }
    }

    // ===== 底栏渲染级隐藏「我的」（数据保留，头像真实派发可用）=====

    private final AtomicBoolean renderFilterLogged = new AtomicBoolean(false);

    /**
     * 底栏渲染隐藏：HomeBottomTabContainerKt（dex 名 bottomtab.g）的容器 Compose 函数
     * a(11参, p1=List tabs, p9=Composer, p10=int)。BEFORE 把 List 参数换成去掉「我的」
     * 的副本——只影响画出来的 tab，数据列表/pager 里「我的」页原样保留，头像仍可由
     * 动作总线派发（6.4.0/6.5.0 的 {@code w0(FC1.c(mineSlotIndex)))）或宿主路由动作
     * （6.6.0 的 {@link #dispatchMineRoute}）打开完整页。
     * 若被删项恰是选中项（头像刚派发过），用 KC1.d 自家 copy 工厂
     * d.a(item,null,null,true,false,65519) 克隆首项置选中，避免底栏无高亮。
     *
     * 这个钩子同时是**渲染快照**唯一的写入点（renderedTabCount / renderedMineIndex）：
     * 隐藏之后底栏那一帧到底画几格、「我的」在不在里面，只有这里知道，
     * 头像的合成点击必须按这份快照定位（按数据层槽位算就会点到左边那一格，
     * 6.6.0 实机就是这么点进动态页的）。6.5.0 起容器 lambda 化且首帧即捕获 List，
     * 渲染级隐藏存在首帧竞态、11 参签名也没了——新版上本 hook 认不到签名就不装，
     * 快照保持 -1（=未知），合成点击自然不会被误用。
     */
    private void installBottomBarRenderFilter(ClassLoader loader) throws Throwable {
        Class<?> g = api.load(loader, "tv.danmaku.bili.khome.widget.bottomtab.g");
        Method target = null;
        for (Method mm : g.getDeclaredMethods()) {
            Class<?>[] ps = mm.getParameterTypes();
            if (ps.length == 11 && ps[1] == List.class
                    && ps[9].getName().contains("Composer") && ps[10] == int.class) {
                target = mm;
                break;
            }
        }
        if (target == null) {
            api.warn("khome: bottom tab container fn (11-arg) not found on " + g.getName()
                    + " -> render-level mine hide not effective on this version (6.5.0+"
                    + " container is a compose lambda), skip; mine tab stays default-visible");
            return;
        }
        api.deoptimize(target);
        api.addHook("khome: bottom tab render", target, new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    Object listObj = chain.getArg(1);
                    if (listObj instanceof List && !((List<?>) listObj).isEmpty()) {
                        List<?> list = (List<?>) listObj;
                        int mineIdx = renderedIndexOfMine(list);
                        if (!api.isHomeTabbarRemoveMine() || mineIdx < 0) {
                            // 不隐藏（或这帧里压根没有「我的」）：画的就是这份列表。
                            renderedTabCount = list.size();
                            renderedMineIndex = mineIdx;
                        } else {
                            ArrayList<Object> kept = new ArrayList<Object>(list);
                            Object removed = kept.remove(mineIdx);
                            // 选中位修补：被隐藏项是选中项 → 克隆首项置选中（只用副本，不动共享对象）
                            if (kept.size() > 0 && isItemSelectorTrue(removed)) {
                                Object clone = cloneItem(kept.get(0), true);
                                if (clone != null) {
                                    kept.set(0, clone);
                                }
                            }
                            renderedTabCount = kept.size();
                            renderedMineIndex = -1;
                            if (renderFilterLogged.compareAndSet(false, true)) {
                                api.info("khome: render hides mine tab (bar " + list.size()
                                        + "->" + kept.size() + ", data keeps " + mineSlotIndex + ")");
                            }
                            java.util.List<Object> args = chain.getArgs();
                            Object[] newArgs = args.toArray();
                            newArgs[1] = kept;
                            return chain.proceed(newArgs);
                        }
                    }
                } catch (Throwable t) {
                    api.error("khome: render filter failed", t);
                }
                return chain.proceed();
            }
        });
        api.info("khome: render filter hook ok -> " + g.getName() + "." + target.getName());
    }

    /** 这一帧底栏列表里「我的」的下标（按 tab 路由名认），没有则 -1。 */
    private int renderedIndexOfMine(List<?> list) {
        Field nf = khomeItemNameField;
        if (nf == null) {
            return -1;
        }
        for (int i = 0; i < list.size(); i++) {
            try {
                Object n = nf.get(list.get(i));
                if (n instanceof String && ((String) n).toLowerCase().contains("user_center")) {
                    return i;
                }
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    private boolean isItemSelectorTrue(Object item) {
        try {
            for (Field f : item.getClass().getDeclaredFields()) {
                if (f.getType() == boolean.class
                        && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    if (f.getBoolean(item)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** KC1.d copy 工厂 d.a(item,null,null,selected,false,65519)（形状匹配，失败返回 null）。 */
    private Object cloneItem(Object item, boolean selected) {
        try {
            for (Method mm : item.getClass().getDeclaredMethods()) {
                Class<?>[] ps = mm.getParameterTypes();
                if (!java.lang.reflect.Modifier.isStatic(mm.getModifiers())
                        || ps.length != 6 || ps[0] != item.getClass()
                        || ps[3] != boolean.class || ps[4] != boolean.class || ps[5] != int.class) {
                    continue;
                }
                mm.setAccessible(true);
                return mm.invoke(null, item, null, null, selected, false, 65519);
            }
        } catch (Throwable t) {
            api.warn("khome: clone item failed: " + t);
        }
        return null;
    }

    // ===== 顶栏消息角标（未读数红点）=====

    private void startBadgePoller() {
        if (badgePoller != null) {
            return;
        }
        badgePoller = new Runnable() {
            @Override public void run() {
                try {
                    updateBadge();
                } catch (Throwable t) {
                    if (badgeProbe.compareAndSet(false, true)) {
                        api.error("homeux: badge update failed", t);
                    }
                }
                uiHandler.postDelayed(this, 4000L);
            }
        };
        uiHandler.postDelayed(badgePoller, 1500L);
    }

    private void updateBadge() {
        TextView badge = msgBadgeView;
        if (badge == null) {
            return;
        }
        int n = readUnreadCount();
        if (n < 0) {
            return; // 数据源不可用，保持原状
        }
        if (n > 0) {
            badge.setText(n > 99 ? "99+" : String.valueOf(n));
            badge.setVisibility(View.VISIBLE);
        } else {
            badge.setVisibility(View.GONE);
        }
    }

    /**
     * 读 IM 未读数：IMBadgeUnreadDataStore 静态 StateFlow（9100300 实测字段 h，
     * 兜底试 i）→ getValue() → loader.a（int 字段，消息 tab 角标同源）。
     * 类名/字段缺失时返回 -1（旧构建无此管线）。
     */
    private int readUnreadCount() {
        Class<?> store = null;
        ClassLoader uiCl = mainUiLoader;
        if (uiCl != null) {
            try {
                store = api.load(uiCl, "com.bilibili.bplus.im.badge.IMBadgeUnreadDataStore");
            } catch (Throwable ignored) {
            }
        }
        if (store == null) {
            try {
                store = api.load(cl, "com.bilibili.bplus.im.badge.IMBadgeUnreadDataStore");
            } catch (Throwable t) {
                return -1;
            }
        }
        for (String fn : new String[]{"h", "i"}) {
            try {
                Field f = store.getDeclaredField(fn);
                f.setAccessible(true);
                Object flow = f.get(null);
                if (flow == null) {
                    continue;
                }
                Object val = flow.getClass().getMethod("getValue").invoke(flow);
                if (val == null) {
                    continue;
                }
                try {
                    Field cf = val.getClass().getDeclaredField("a");
                    cf.setAccessible(true);
                    return cf.getInt(val);
                } catch (Throwable ignored) {
                    // 字段名漂移兜底：取第一个正数 int 字段
                }
                for (Field ff : val.getClass().getDeclaredFields()) {
                    if (ff.getType() != int.class) {
                        continue;
                    }
                    ff.setAccessible(true);
                    int v = ff.getInt(val);
                    if (v > 0) {
                        return v;
                    }
                }
                return 0;
            } catch (Throwable ignored) {
                // 试下一个字段
            }
        }
        return -1;
    }

    // ===== 底栏 tab 过滤 =====

    private void installTabListFilter(ClassLoader uiCl) throws Throwable {
        // 锚点（9100100/9100300 双构建验证）：MainFragment.Zl() 的「返回类型」就是
        // tab provider 类（9100100=S、9100300=P，单字母类名随构建重排——设备版 S
        // 是无关的登录类），provider 实现 BaseMainFrameFragment$n，其「无参返回
        // ArrayList」方法产出底栏+pager 的 tab 模型列表（S.a()/P.a()）。
        // 关键：不调用 Zl，只取 getReturnType() 即拿到 provider 类，再按签名 hook。
        // 全失败打印候选清单（PITFALLS #8 纪律）。
        Class<?> mf = api.load(uiCl, "tv.danmaku.bili.ui.main2.MainFragment");
        Method zl = null;
        for (Method mm : mf.getDeclaredMethods()) {
            if (mm.getParameterTypes().length != 0) {
                continue;
            }
            if ("Zl".equals(mm.getName())) {
                zl = mm;
                break;
            }
        }
        if (zl == null) {
            // 同样是 main2（6.3.0/6.4.0）专属路径：6.5.0+ 底栏走 khome，这里 miss 属预期。
            api.debug("homeux: MainFragment.Zl() not found (legacy main2 path; khome covers 6.5.0+)");
            return;
        }
        Class<?> providerCls = zl.getReturnType();
        if (providerCls == null || Void.TYPE.equals(providerCls) || providerCls.isPrimitive()) {
            api.error("homeux: Zl() return type is not a provider class: " + zl, null);
            return;
        }
        Method target = null;
        StringBuilder candidates = new StringBuilder();
        for (Method mm : providerCls.getDeclaredMethods()) {
            if (mm.getParameterTypes().length != 0) {
                continue;
            }
            if (!mm.getReturnType().getName().equals("java.util.ArrayList")) {
                continue;
            }
            candidates.append(mm.getName()).append("(), ");
            if (target == null) {
                target = mm;
            }
        }
        if (target == null) {
            api.error("homeux: tab provider list method not found on "
                    + providerCls.getName() + ", candidates: "
                    + (candidates.length() == 0 ? "none(no-arg ArrayList)" : candidates.toString()), null);
            return;
        }
        api.deoptimize(target);
        api.addHook("homeux: tab list provider", target, new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                try {
                    result = filterTabList(result);
                } catch (Throwable t) {
                    api.error("homeux: filter tab list failed", t);
                }
                return result;
            }
        });
        api.info("homeux: tab list filter hook ok -> " + providerCls.getName() + "." + target.getName() + "()");
    }

    private Object filterTabList(Object list) throws Exception {
        if (!(list instanceof List)) {
            return list;
        }
        List<?> items = (List<?>) list;
        boolean rmMsg = api.isHomeTabbarRemoveMessage();
        boolean rmMine = api.isHomeTabbarRemoveMine();
        boolean probe = tabListProbe.compareAndSet(false, true);
        if (!rmMsg && !rmMine && !probe) {
            return list;
        }
        ArrayList<Object> kept = new ArrayList<Object>();
        StringBuilder sb = probe ? new StringBuilder("homeux: tabs[") : null;
        int removedMsg = 0;
        int removedMine = 0;
        int droppedMaxIdx = -1;
        int keptMinAfterDrop = Integer.MAX_VALUE;
        for (int i = 0; i < items.size(); i++) {
            Object item = items.get(i);
            String url = pageUrlOf(item);
            if (probe && sb != null) {
                sb.append(url).append(",");
            }
            boolean drop = false;
            if (url != null) {
                if (rmMsg && startsWithAny(url, TAB_URL_REMOVE_MESSAGE)) {
                    drop = true;
                    removedMsg++;
                } else if (rmMine && startsWithAny(url, TAB_URL_REMOVE_MINE)) {
                    mineTabUrl = url; // 记下真实 url 供顶栏头像导航
                    drop = true;
                    removedMine++;
                }
            }
            if (drop) {
                droppedMaxIdx = Math.max(droppedMaxIdx, i);
            } else {
                if (droppedMaxIdx >= 0) {
                    keptMinAfterDrop = Math.min(keptMinAfterDrop, i);
                }
                kept.add(item);
            }
        }
        if (probe && sb != null) {
            sb.append("] rmMsg=").append(removedMsg).append(" rmMine=").append(removedMine);
            api.info(sb.toString());
        }
        if (removedMsg == 0 && removedMine == 0) {
            return list;
        }
        // 尾缀守卫：只允许移除列表尾部的连续项（bar 索引与 pager 索引保持一致）。
        // 若被删项之后还有保留项（服务端调整了顺序），过滤会造成索引错位——放弃并告警。
        if (keptMinAfterDrop < Integer.MAX_VALUE && keptMinAfterDrop < droppedMaxIdx) {
            api.warn("homeux: removed tabs are not at list tail (kept idx "
                    + keptMinAfterDrop + " after dropped idx " + droppedMaxIdx
                    + ") - skip filtering to avoid index mismatch");
            return list;
        }
        api.info("homeux: tab list filtered " + items.size() + " -> " + kept.size()
                + " (msg=" + removedMsg + " mine=" + removedMine + ")");
        return kept;
    }

    /**
     * 从 tab 模型对象里找 pageUrl：字段名随构建漂移，改为字段名无关扫描——
     * 第一层扫 item 的对象字段（resource.x），第二层在该对象里找以
     * "bilibili://" 开头的 String 字段。都找不到返回 null（fail-open 不过滤）。
     */
    private String pageUrlOf(Object tabItem) {
        // 9100300 Yf0.l（HomeTabInfo）：真实字段 a=tab_id b=tab_name c=tab_url
        // d=home_tab_url(=bilibili://home?...)。直接读 c，并排除 home_tab_url 干扰。
        try {
            Field cf = tabItem.getClass().getDeclaredField("c");
            cf.setAccessible(true);
            Object cv = cf.get(tabItem);
            if (cv instanceof String) {
                String s = (String) cv;
                if (s.startsWith("bilibili://") && !s.startsWith("bilibili://home?")) {
                    return s;
                }
            }
        } catch (NoSuchFieldException ignored) {
            // 旧构建无此字段，走下面的嵌套扫描
        } catch (Throwable ignored) {
        }
        try {
            for (Field f : tabItem.getClass().getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                f.setAccessible(true);
                Object v = f.get(tabItem);
                if (v == null || v instanceof String || v instanceof Number
                        || v instanceof Boolean || v instanceof Character) {
                    continue;
                }
                String route = findRouteString(v);
                if (route != null) {
                    return route;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 在对象自身的 String 字段里找 bilibili:// 路由。 */
    private String findRouteString(Object obj) {
        try {
            for (Field f : obj.getClass().getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        || f.getType() != String.class) {
                    continue;
                }
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v instanceof String) {
                    String s = (String) v;
                    if (s.startsWith("bilibili://")) {
                        return s;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object getDeclaredField(Object obj, String name) {
        try {
            Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean startsWithAny(String url, String[] prefixes) {
        for (String p : prefixes) {
            if (url.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** 代码画的信封图标：不依赖目标 App 资源，避免资源名漂移。 */
    private static final class EnvelopeDrawable extends Drawable {
        private final int color;

        EnvelopeDrawable(int color) {
            this.color = color;
        }

        @Override public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float w = b.width() * 0.62f;
            float h = b.height() * 0.44f;
            float left = b.centerX() - w / 2;
            float top = b.centerY() - h / 2;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(2f, b.width() * 0.045f));
            p.setColor(color);
            RectF rect = new RectF(left, top, left + w, top + h);
            canvas.drawRoundRect(rect, w * 0.12f, w * 0.12f, p);
            Path flap = new Path();
            flap.moveTo(left, top + h * 0.12f);
            flap.lineTo(b.centerX(), top + h * 0.62f);
            flap.lineTo(left + w, top + h * 0.12f);
            canvas.drawPath(flap, p);
        }

        @Override public void setAlpha(int alpha) {
        }

        @Override public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
