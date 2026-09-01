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
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.github.libxposed.api.XposedInterface;

/**
 * 首页 UI 布局调整（v1.7.0）：对齐国内版布局。
 *
 *  - 顶栏左侧头像（原本无点击行为）改为「我的」入口：透明点击层盖住头像区域，
 *    点击走 bilibili://user_center/mine（实测落点=底栏「我的」同款页面）。
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

    /** tab_host ComposeView 的资源 id（0x7f0938b4，设备版 uiautomator 实测同名同 id）。 */
    private static final int TAB_HOST_VIEW_ID = 0x7f0938b4;

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
                        api.info("homeux: MainFragment loaded by " + uiCl);
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
        api.info("homeux: main2 funnels installed under " + uiCl);
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

        // 容器：叠在顶栏内容行之上。父容器 HomeAppBarLayout 是「垂直」LinearLayout，
        // 直接 addView 会新开一行（实测）——用负 topMargin 把本层拉回到上一个子 View
        // （折叠 ComposeView）的位置上，高度与其同步（净占位为 0，不撑高父容器）。
        final FrameLayout overlay = new FrameLayout(bar.getContext());
        overlay.setTag("bili_tamer_top_overlay");
        overlay.setClickable(false);
        final android.widget.LinearLayout.LayoutParams overlayLp = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0);
        overlayLp.topMargin = 0;
        barGroup.addView(overlay, barGroup.getChildCount(), overlayLp);
        barGroup.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                                 int ol, int ot, int or2, int ob) {
                try {
                    if (barGroup.getChildCount() > 1) {
                        int h = barGroup.getChildAt(0).getHeight();
                        android.widget.LinearLayout.LayoutParams lp =
                                (android.widget.LinearLayout.LayoutParams) overlay.getLayoutParams();
                        if (lp != null && (lp.height != h || lp.topMargin != -h)) {
                            lp.height = h;
                            lp.topMargin = -h;
                            overlay.setLayoutParams(lp);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        });

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
        if (api.isHomeTabbarRemoveMessage() || api.isHomeTabbarRemoveMine()) {
            bar.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        applyTabRemoval(barGroup);
                    } catch (Throwable t) {
                        api.error("homeux: applyTabRemoval failed", t);
                    }
                }
            }, 3000L);
        }
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
     * 头像点击：优先走 HomeTabServiceImpl 的 tab 点击事件分发（q：5 参
     * (boolean,int,String,View,Bundle) 方法，把 url 派发给主框架监听器，效果等同
     * 真实点底栏「我的」tab，页面完整）；深链 bilibili://user_center/mine 会打开
     * GeneralActivity 独立壳（实测缺底部功能区），只作兜底。
     */
    private void openMineEntry(View v) {
        String url = mineTabUrl != null ? mineTabUrl : "bilibili://user_center/mine";
        // 首选：合成一次对底栏「我的」tab 的真实点击（页面 = 完整 tab 页面，含底部功能区）。
        if (mineTabKept && tapBottomTab(v, (mineSlotIndex + 0.5f) / Math.max(1, keptTabCount))) {
            api.info("homeux: avatar -> synthesized tap on mine tab (slot " + mineSlotIndex
                    + "/" + keptTabCount + ")");
            return;
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
        api.info("homeux: live MainFragment loader=" + uiCl);
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
            api.error("homeux: tab model list field not found on " + mainFrag.getClass().getName(), null);
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
            api.error("homeux: MainFragment.Zl() not found - cannot locate tab provider", null);
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
