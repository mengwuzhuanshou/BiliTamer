package com.tamer.bili.hooks;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * 国际版首页「直播板块」入口解锁（6.3.0–6.6.0，2026-09-30 dex 定稿）。
 *
 * 现象：首页顶栏只有 推荐/动画，没有国内版的 直播 频道页。板块本体是随包的
 * （com.bilibili.bililive.biz.livehome.videofeed.fragment.LiveTabFragment，
 * classes14 注册路由 bilibili://live/home，内部 LiveHomeFragment/LiveVideoFeedFragment
 * 全套都在），只是当前身份拿不到入口 —— 属于「有功能、按用户裁剪入口」。
 *
 * 根因（不是客户端过滤）：首页顶栏 tab 的唯一数据源是
 * GET https://app.bilibili.com/x/resource/show/tab/v2（HomeTabServiceKt，
 * query: access_key/ver/interest_result），响应 data 的五个列表按 kotlinx 生成的
 * HomeTabData 落成 a=top b=tab c=bottom d=top_more e=top_left。客户端只做两件事：
 * ① HomeFrameViewModel 把 data.tab（field b，flow f374093e）做成 StateFlow；
 * ② home.tab.components.pagebuild.PageBuildComponent 把它映射成页/路由。
 * 也就是说：直播不是被这道 filter 滤掉的 —— 服务端下发给国际版的 tab 列表里
 * 根本没有那一项（同一接口用国内/i18n 身份取到的配置里
 * {id:39,name:"直播",uri:"bilibili://live/home",tab_id:"直播tab",pos:1} 就在 pos 1，
 * 推荐 pos 2 且 default_selected=1，是国内版的原样形状）。
 * 反向佐证：用 deeplink 直接拉 bilibili://live/home，GeneralActivity 能解析并实例化
 * LiveTabFragment（id/liveTabContainer 出现，只是内容为空）——路由和板块都是好的，
 * 缺的只是那条 tab 记录。空页是因为 LiveTabFragment 的 bm/cm/dm/Wl 这些「作为首页 tab
 * 被框架驱动」的回调没人调；把它当真 tab 注进去，框架会自己把它喂起来。
 *
 * 落点：{@code PageBuildComponent.w(List)} 的**入参**。这是 6.6.0 dex 里唯一同时喂到
 * 两条渲染支路的收口（dexcall/反编译逐点确认，全 apk 只有三处调用）：
 *  - {@code PageBuildComponent$initPageData$1$1}（combine(顶栏 tab 流, HOME_TAB_SERVICE
 *    可见性图)）→ w() → List<gE1.j> → v0(bE1.n) → **顶栏标题**；
 *  - {@code PageBuildComponent$setupViewPager$dataJob$1$1}（直接 collect 同一条流）
 *    → w() → 每条 b 的 uri → {@code adapter.T(arrayList)} → **ViewPager2 的页**，
 *    并且 {@code HomePagerConstraintLayout.setPagingEnabled}(页>1) 也吃这一份。
 * 补在 w() 的入参上，标题和页必然同源；点标题时走的是
 * {@code PageBuildComponent.k()}：{@code indexOf(adapter.e() 里的 uri)} 求页下标，
 * 页列表里没有那条 uri 就直接 return ——「点了没反应」正是这条 return。
 *
 * 这条落点是实机踩出来的，前两种写法都不成立（都留下过证据，别再试回去）：
 *  ① 只补 {@code $initPageData$1$1} 的 args[0]：**标题 3 个、pager 2 页**。
 *     追加时第三格（直播）下标越界 → 点它没有任何反应；
 *     头部插入时标题整体右移一格 → 点「直播」出推荐流、点「推荐」出动画流。
 *     同一份错位在一台机器上复现、另一台不复现，别把「没复现」当成「没问题」。
 *  ② 以为 w() 把注入项滤掉了：错。带 uri 明细的一次性探针实测
 *     {@code in=3 out=3 out_uris=[promo, pgc, live] nothing dropped}，
 *     路由解析（{@code T.a}）对 bilibili://live/home 是过的。
 *  ③ 以为「顶栏标题」和「pager 页面」各有**一份 PageBuildComponent**
 *     （{@code home.tab.components.pagebuild} 与 {@code home.components.pagebuild}）：
 *     也错。后者的本体被 R8 改成了 {@code mi0.a}（按真名 load 直接
 *     ClassNotFoundException），而且它做的是**底栏**页（{@code gE1.d} +
 *     key_main_tab_* 配置），与顶栏无关。顶栏这两条支路共用同一个组件、同一个 w()。
 *
 * 列表里那串 Map<String,Boolean> 是按 uri 首段（{@code t.I0(uri, '/')}）取的开关图，
 * 缺省即 true（{@code n.f(map.get(seg), TRUE)}），注入项不需要注册。
 *
 * 抗漂移口径（PITFALLS #16：R8 每版重排单字母包名，旧名会被无关类复用）：
 * 锚点组件 {@code tv.danmaku.bili.home.tab.components.pagebuild.PageBuildComponent}
 * 是保留真名的宿主类，6.3.0/6.4.0/6.5.0/6.6.0 四个包逐字相同，直接按名加载；
 * 页过滤方法在四个版本上也都叫 {@code w(List)->List}（dexscan 逐版列过：该类上
 * (List)-&gt;List 形状的实例方法**只有这一个**，所以既按名认、也可按形状认，
 * 名字漂了就退到唯一形状，形状多义就直接放弃不开）。
 * 但 HomeTabItemData 的类名每版都换（6.3.0=k、6.4.0=n、6.5.0=nD1.k、6.6.0=fE1.k），
 * 字段字母四版同序（a=id b=name c=uri d=icon e=icon_selected f=default_selected
 * g=pos h=tab_id m=type）—— 已用 dexmethod 逐条对着字节码核过：底栏 lambda 里与常量 3
 * （RestrictedType.LESSONS）比的是 m:I（type），扫默认页的是 f:I（default_selected），
 * 解析路由前读的是 c:String（uri），三处 6.3.0/6.4.0/6.5.0/6.6.0 一致。
 * 即便如此，字段名仍只当入口，真正验收的是**值**：原型项的 c 必须含 "://"（uri），
 * a 必须是纯数字（服务端 id），b 必须是非空且不含 "://" 的文案。三条同时成立才注入，
 * 否则只记一条 warn 并原样放行（宁可漏挂也不挂错：挂错就是把随机字符串塞进顶栏）。
 *
 * 实机复核：6.6.0(9130300) 在两台不同厂商的机器上值校验三条全过，
 * 且两台的账号拿到的顶栏都只有「推荐/动画」两项 —— 与「服务端按身份裁剪 tab 列表」的
 * 定性一致，也说明字段字母序在这两台的包上未漂移。
 *
 * 注入位置选**追加**：既有下标一个都不动，只多出一个新下标（头部插入在 w() 入参上
 * 不会像早先那样把标题与 pager 错开，但对「当前页/待选中路由」的影响仍是最大的，
 * 没必要冒）。pos 同理跟到末尾（只在全部落在服务端合理区间 1..64 时才写，
 * 万一某版字母错位也不至于改掉别的 int 字段）。
 *
 * 影响面：宿主每收到一次顶栏 tab 配置就把那份列表补一项，标题与 pager 同源，
 * 零监听、零轮询、不改服务端数据、不动别的 tab；开关关闭即完全恢复原行为。
 */
public final class LiveTabHooks {

    /**
     * 顶栏页构建组件（真名，6.3.0/6.4.0/6.5.0/6.6.0 逐字相同）。
     * 它的 {@code w(List)} 是顶栏「标题」与「pager 页面」两条支路的公共收口，
     * 注入点就设在它的入参上。
     */
    private static final String PAGE_BUILDER =
            "tv.danmaku.bili.home.tab.components.pagebuild.PageBuildComponent";

    /** 页过滤方法在四个版本上的现名（6.3.0–6.6.0 都是 w）；找不到再退回唯一形状匹配。 */
    private static final String PAGE_FILTER_NAME = "w";

    /** 国内版同款入口：路由在 classes14 的 BR_live_homeKt 里注册，指向 LiveTabFragment。 */
    private static final String LIVE_URI = "bilibili://live/home";
    private static final String LIVE_NAME = "直播";
    /** 注入项自己的 tab_id，便于在日志/宿主状态里认出它不是服务端下发的那条。 */
    private static final String LIVE_TAB_ID = "liv_tab_unlock";

    private final HookApi api;
    private final ClassLoader cl;
    private final java.util.concurrent.atomic.AtomicBoolean injectedProbe =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean shapeWarned =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public LiveTabHooks(HookApi api, ClassLoader cl) {
        this.api = api;
        this.cl = cl;
    }

    public void install() {
        try {
            Class<?> c = api.load(cl, PAGE_BUILDER);
            Method w = findPageFilter(c);
            if (w == null) {
                return;
            }
            api.deoptimize(w);
            api.addHook("livetab: " + PAGE_BUILDER + "#" + w.getName(), w,
                    new XposedInterface.Hooker() {
                        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            return gated(chain);
                        }
                    });
            api.info("livetab: hook ok on " + PAGE_BUILDER + "#" + w.getName() + "(List)");
        } catch (Throwable t) {
            api.warn("livetab: anchor unavailable: " + t);
        }
    }

    /**
     * 按名认 {@code w}，名字漂了就退到「实例方法里唯一的 (List)-&gt;List」；仍然多义就放弃并
     * 打印候选（宁可整个功能不开，也不能开成顶栏与 pager 各拿一份列表——那正是
     * 「直播点不动」的成因）。
     */
    private Method findPageFilter(Class<?> c) {
        Method byShape = null;
        int shapeHits = 0;
        StringBuilder cands = new StringBuilder();
        for (Method m : c.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                    || p.length != 1 || !List.class.isAssignableFrom(p[0])
                    || !List.class.isAssignableFrom(m.getReturnType())) {
                continue;
            }
            shapeHits++;
            cands.append(' ').append(m.getName());
            if (m.getName().equals(PAGE_FILTER_NAME)) {
                return m;
            }
            if (byShape == null) {
                byShape = m;
            }
        }
        if (byShape != null && shapeHits == 1) {
            api.info("livetab: page filter renamed, using " + byShape.getName() + "(List)");
            return byShape;
        }
        api.warn("livetab: page filter (List)->List not pinned on " + c.getName()
                + " (hits=" + shapeHits + ":" + cands + ") -> leave tabs untouched");
        return null;
    }

    private Object gated(XposedInterface.Chain chain) throws Throwable {
        if (!api.isLiveTabUnlockEnabled()) {
            return chain.proceed();
        }
        Object[] args;
        try {
            args = chain.getArgs().toArray();
        } catch (Throwable t) {
            return chain.proceed();
        }
        List<?> modified = inject(args.length > 0 ? args[0] : null);
        if (modified == null) {
            return chain.proceed();
        }
        Object[] next = chain.getArgs().toArray();
        next[0] = modified;
        return chain.proceed(next);
    }

    /** 返回替换后的列表；任何前提不成立（含服务端已下发直播）都返回 null = 原样放行。 */
    private List<?> inject(Object arg0) {
        try {
            if (!(arg0 instanceof List)) {
                return null;
            }
            List<?> src = (List<?>) arg0;
            if (src.isEmpty()) {
                return null;
            }
            Object proto = src.get(0);
            if (proto == null) {
                return null;
            }
            Class<?> ic = proto.getClass();
            Field idF = ic.getDeclaredField("a");
            Field nameF = ic.getDeclaredField("b");
            Field uriF = ic.getDeclaredField("c");
            Field posF = ic.getDeclaredField("g");
            if (idF.getType() != String.class || nameF.getType() != String.class
                    || uriF.getType() != String.class || posF.getType() != int.class) {
                warnShape(ic, "field type");
                return null;
            }
            setAccessible(idF, nameF, uriF, posF);
            String protoUri = (String) uriF.get(proto);
            String protoId = (String) idF.get(proto);
            String protoName = (String) nameF.get(proto);
            if (protoUri == null || !protoUri.contains("://")
                    || protoId == null || !isDigits(protoId)
                    || protoName == null || protoName.indexOf("://") >= 0) {
                warnShape(ic, "field value");
                return null;
            }
            for (Object o : src) {
                String u = (String) uriF.get(o);
                if (u != null && u.startsWith(LIVE_URI)) {
                    return null;   // 服务端已经把直播下发给这个身份了
                }
            }
            Constructor<?> ctor = ic.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object item = ctor.newInstance();
            uriF.set(item, LIVE_URI);
            nameF.set(item, LIVE_NAME);
            idF.set(item, String.valueOf(maxId(src, idF) + 1));
            // tab_id 只用于人肉识别。
            Field tabIdF = ic.getDeclaredField("h");
            if (tabIdF.getType() == String.class) {
                tabIdF.setAccessible(true);
                tabIdF.set(item, LIVE_TAB_ID);
            }
            // **追加到末尾，不插头部**：既有页的下标一个都不动，只有新下标是新建页，
            // 对「当前选中页 / 待选中路由（f373064p）」的影响最小。
            // pos 同理跟到末尾（只在全部落在服务端合理区间 1..64 时才写，
            // 万一某版字母错位也不至于改掉别的 int 字段）。
            int maxPos = 0;
            boolean posSane = true;
            for (Object o : src) {
                int p = posF.getInt(o);
                if (p < 1 || p > 64) {
                    posSane = false;
                    break;
                }
                if (p > maxPos) {
                    maxPos = p;
                }
            }
            if (posSane) {
                posF.setInt(item, maxPos + 1);
            }
            // f=default_selected、m=type 保持无参构造的 0：不被选为默认页，也不走 LESSONS 分支。
            ArrayList<Object> out = new ArrayList<Object>(src.size() + 1);
            out.addAll(src);
            out.add(item);
            if (injectedProbe.compareAndSet(false, true)) {
                api.info("livetab: appended " + LIVE_URI + " after " + src.size()
                        + " tab(s) " + names(src, nameF));
            }
            return out;
        } catch (Throwable t) {
            api.debug("livetab: inject skipped: " + t);
            return null;
        }
    }

    private void warnShape(Class<?> ic, String why) {
        if (shapeWarned.compareAndSet(false, true)) {
            api.warn("livetab: item shape not confirmed (" + why + ") on "
                    + ic.getName() + " -> leave tabs untouched");
        }
    }

    private static String names(List<?> src, Field nameF) throws Throwable {
        StringBuilder sb = new StringBuilder();
        for (Object o : src) {
            sb.append(nameF.get(o)).append(' ');
        }
        return sb.toString().trim();
    }

    private static int maxId(List<?> src, Field idF) throws Throwable {
        int max = 0;
        for (Object o : src) {
            String s = (String) idF.get(o);
            if (s != null && isDigits(s)) {
                try {
                    int v = Integer.parseInt(s);
                    if (v > max) {
                        max = v;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return max;
    }

    private static boolean isDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static void setAccessible(Field... fs) {
        for (Field f : fs) {
            f.setAccessible(true);
        }
    }
}
