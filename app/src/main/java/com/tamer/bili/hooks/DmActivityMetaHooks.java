package com.tamer.bili.hooks;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;

/**
 * 干掉「云视听小电视」浮层（{@code player_no_activity_meta}）：移植 MBGA 的
 * {@code vid_player_disable_activity_meta}（{@code setting_vid_player_no_activity_meta}
 * 的中文文案就是「干掉云视听小电视」）。
 *
 * 数据面：播放器活动浮层由弹幕服务 {@code bilibili.community.service.dm.v1.DmView}
 * 回包里的 {@code activity_meta} 带下来，清掉这个 repeated 字段浮层就没有素材。
 *
 * 6.6.0（9130300）取证（dex 直读，未靠探针）：
 *  - {@code com.bapis.bilibili.community.service.dm.v1.DmViewReply} **没被 R8 改名**，
 *    且已换成 protobuf-lite（{@code GeneratedMessageLite}）：{@code activityMeta_} 是
 *    {@code ProtobufList<String>}（老版本是 {@code repeated ActivityMeta} 消息，现在简化成
 *    字符串列表，所以 {@code …dm.v1.ActivityMeta} 这个类型在本宿主根本不存在）；
 *    {@code public void clearActivityMeta()} 就在消息本体上（jadx 反编译第 1262 行），
 *    不需要绕 Builder——**但 jadx 那个 {@code public} 不能信**：它是按代码风格补出来的，
 *    dex 里的真实 access flag 不是 public，第一轮装机 {@code Class.getMethod} 就直接
 *    {@code NoSuchMethodException} 了（见 {@link #member}）。可见性一律按运行时报错定。
 *  - MBGA 的挂点 {@code DmMossKtxKt$suspendDmView$$inlined$suspendCall$1} 也原样存在，
 *    实现 {@code com.bilibili.lib.moss.api.MossResponseHandler}，
 *    {@code onNext(Ljava/lang/Object;)→V} 签名与那代一致——所以这条移植不需要重找锚点。
 *
 * 与 MBGA 的两点差别：
 *  ① 开关判定和清空都放在 hook 里做（它写在 before{} 里，语义一样）；
 *  ② 无论有没有得清都留一条日志，并带上被清空内容的前 96 字符。这条**必须**有：静态读 dex
 *    找 {@code getActivityMetaList()} 的宿主调用点，结果只有 protobuf Builder 自己的委托
 *    （{@code DmViewReply$b}），一度只能怀疑「渲染方根本不读这个 getter、这字段本宿主不用」。
 *    装机把这条推翻了：连续几个稿件里空的和非空的都出现了，非空那条清成功，内容是
 *    {@code {"id":10011,"start":0,"end":5,…,"picture":{"mime":"image","resource":"https://…}}}
 *    ——时间段 + 图片资源，正是浮层素材的形状。宿主不调 getter 是因为 R8 把它内联成了直接
 *    字段访问，所以清 {@code activityMeta_} 照样有效；「查不到调用点」不等于「数据不存在」，
 *    这是 PITFALLS #39 的镜像面（{@link FeedCleanHooks} 撤掉 ③ 老宿主兜底是同族教训）。
 *    另一层只有日志能给：屏幕上浮层没消失，并不必然意味着钩子没跑——本宿主只在活动期下发，
 *    没有「钩子进来了但这批本来就是空的」这行，两种情况在观测上长得一模一样。
 *
 * 验收口径（2026-10-10 实机）：挂点、命中、清空三步有日志为证；**浮层是否真的从屏上消失**
 * 要在活动期拿同一稿件做开关 A/B 肉眼看，本轮未证，开关描述里也没声称它已证。
 *
 * 出厂默认关：它会删掉宿主自己下发的一块界面。
 */
public final class DmActivityMetaHooks {

    /** dm.v1 的 Moss 协程内联回调类（MBGA 同代锚点，6.6.0 实测类名未漂）。 */
    private static final String HANDLER_CLZ =
            "com.bapis.bilibili.community.service.dm.v1.DmMossKtxKt$suspendDmView$$inlined$suspendCall$1";
    private static final String REPLY_CLZ =
            "com.bapis.bilibili.community.service.dm.v1.DmViewReply";

    /** moss 系类是懒加载的：安装期没就绪就延后重试（与 IpLocationHooks 同法）。 */
    private static final long RETRY_DELAY_MS = 3000L;
    private static final int RETRY_MAX = 5;

    private final HookApi api;
    private final ClassLoader cl;

    /** 首条「清掉了 n 项」与首条「进来了但字段是空的」各印一次。 */
    private final AtomicBoolean clearedLogged = new AtomicBoolean(false);
    private final AtomicBoolean emptyLogged = new AtomicBoolean(false);

    public DmActivityMetaHooks(HookApi api, ClassLoader cl) {
        this.api = api;
        this.cl = cl;
    }

    public void install() {
        installRetry(0);
    }

    private void installRetry(final int attempt) {
        try {
            installNow();
            return;
        } catch (ClassNotFoundException e) {
            if (attempt >= RETRY_MAX) {
                api.error("dm: activity-meta handler still absent after " + RETRY_MAX
                        + " retries -> switch is a no-op on this host build", e);
                return;
            }
            api.debug("dm: moss handler not loaded yet (attempt " + (attempt + 1) + ")");
        } catch (Throwable t) {
            api.error("dm: hook group unavailable: activity meta", t);
            return;
        }
        try {
            api.postDelayed(new Runnable() {
                @Override public void run() {
                    installRetry(attempt + 1);
                }
            }, RETRY_DELAY_MS);
        } catch (Throwable t) {
            api.warn("dm: retry scheduling failed: " + t);
        }
    }

    private void installNow() throws Throwable {
        Class<?> handler = api.load(cl, HANDLER_CLZ);
        Class<?> reply = api.load(cl, REPLY_CLZ);
        final Method clear = member(reply, "clearActivityMeta");
        final Method count = member(reply, "getActivityMetaCount");
        final Method getAt = member(reply, "getActivityMeta", int.class);
        Method onNext = api.publicMethod(handler, "onNext", Object.class);
        api.deoptimize(onNext);
        api.addHook("dm: activity meta", onNext, new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    if (api.isPlayerNoActivityMetaEnabled()) {
                        Object[] args = chain.getArgs().toArray();
                        if (args.length > 0 && reply.isInstance(args[0])) {
                            int n = ((Number) api.invoke(count, args[0])).intValue();
                            if (n > 0) {
                                String sample = metaSample(getAt, args[0]);
                                api.invoke(clear, args[0]);
                                if (clearedLogged.compareAndSet(false, true)) {
                                    api.info("dm: cleared " + n + " activity_meta entr"
                                            + (n == 1 ? "y" : "ies")
                                            + " from the dm view reply, first entry=" + sample);
                                }
                            } else if (emptyLogged.compareAndSet(false, true)) {
                                api.info("dm: activity-meta hook is live but this reply carried"
                                        + " no activity_meta -> nothing to clear on this batch;"
                                        + " if the overlay still shows, the host no longer feeds"
                                        + " it from dm.v1");
                            }
                        }
                    }
                } catch (Throwable t) {
                    api.error("dm: activity meta clear failed", t);
                }
                return chain.proceed();
            }
        });
        api.info("dm: activity meta hook ready on " + handler.getName() + ".onNext");
    }

    /**
     * 清空前取一条内容摘要：这是判「这条 repeated string 到底是不是那个浮层」唯一能拿到的
     * 现场依据（静态侧只能证到字段名）。只在首条非空回包时打一次，截 96 字符。
     */
    private String metaSample(Method getAt, Object replyObj) {
        try {
            Object v = api.invoke(getAt, replyObj, Integer.valueOf(0));
            if (!(v instanceof String)) {
                return String.valueOf(v);
            }
            String s = (String) v;
            return s.length() <= 96 ? s : s.substring(0, 96) + "...";
        } catch (Throwable t) {
            return "<unreadable:" + t.getClass().getSimpleName() + ">";
        }
    }

    /**
     * protobuf-lite 把 {@code clearActivityMeta()} / {@code getActivityMetaCount()} 生成在
     * **消息本体**上、且**不是 public**（只给自家 Builder 委托用），所以
     * {@code Class.getMethod} 会抛 {@code NoSuchMethodException}——装机第一轮就是这么炸的。
     * 先按 public 找（老宿主与将来的改版可能是 public），找不到再按声明找并开可见性：
     * 目标是宿主自己的应用类，不是框架隐藏接口，{@code setAccessible} 在这条路上没有限制。
     */
    private Method member(Class<?> cls, String name, Class<?>... params) throws NoSuchMethodException {
        try {
            return api.publicMethod(cls, name, params);
        } catch (NoSuchMethodException e) {
            Method m = api.declaredMethod(cls, name, params);
            m.setAccessible(true);
            return m;
        }
    }
}
