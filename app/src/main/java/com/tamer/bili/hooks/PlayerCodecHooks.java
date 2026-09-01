package com.tamer.bili.hooks;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * 播放器解码（HEVC / AV1）与音质（Hi-Res 无损 / 杜比全景声 / AAC）选择。
 *
 * 解码自动顺位按设备硬解能力过滤<b>请求位</b>（CodecCapability，v1.6.1 黑屏修复）：
 * 设备没有硬件解码器的编码不写入 fnval，服务端即不下发对应流。**只过滤请求，
 * 不替换解码**——自动时的顺位选择仍完全交给原逻辑在实发流集合上自行回退，
 * 锁定项是用户显式选择也不过滤（仅告警）。
 *
 * 6.3.0 落点：
 *  - fnval 位控制服务端下发哪些格式的流。hook FG1.b 的 fnval 计算（int c() 与 long d()）
 *    按设置强制开启对应位，服务端才会下发 HEVC/AV1/Dolby 流。
 *  - 视频解码偏好：GeminiCommonResolverParams.c() 返回 VideoCodecType（其 y 字段为
 *    codecid：7=AVC, 12=HEVC, 13=AV1）。hook 该方法实现顺位/锁定。
 *  - 音质顺位：MediaResource.I(int,int) 构建 IjkMediaAsset 时选择默认音轨 id。
 *    杜比(DOLBY) 与 Hi-Res(HIRES) 音频流以 AudioEnhancementResource 挂在
 *    mediaResource.l / mediaResource.m。hook I() 把默认音轨指到顺位首个可用项。
 */
public final class PlayerCodecHooks {

    // fnval 位定义（B 站播放器共用约定）
    private static final int FNVAL_DASH  = 0x10;      // 16
    private static final int FNVAL_DOLBY = 0x80;      // 128  Dolby 音频
    private static final int FNVAL_AV1   = 0x200;     // 512  AV1 视频
    private static final int FNVAL_H265  = 0x10000;   // 65536 HEVC 相关位
    private static final int FNVAL_HDR   = 0x40;       // 64    HDR
    private static final int FNVAL_HDR_VIVID = 0x4000; // 16384 HDR Vivid

    // 服务端 codecid：7=AVC(H264) 12=HEVC(H265) 13=AV1 14=H266
    private static final int CODECID_AVC  = 7;
    private static final int CODECID_HEVC = 12;
    private static final int CODECID_AV1  = 13;

    private final HookApi api;
    private final ClassLoader cl;
    private final java.util.concurrent.atomic.AtomicBoolean probeFnval =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** 设备硬解能力只打一条日志（进程生命周期内去重）。 */
    private final java.util.concurrent.atomic.AtomicBoolean hwCapLogged =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public PlayerCodecHooks(HookApi api, ClassLoader cl) {
        this.api = api;
        this.cl = cl;
    }

    public void install() {
        installGroup("fnval", new ThrowingAction() {
            @Override public void run() throws Throwable {
                installFnval();
            }
        });
        installGroup("codec preference", new ThrowingAction() {
            @Override public void run() throws Throwable {
                installCodecPreference();
            }
        });
        installGroup("audio default", new ThrowingAction() {
            @Override public void run() throws Throwable {
                installAudioDefault();
            }
        });
        api.info("PlayerCodecHooks installed");
    }

    private void installGroup(String name, ThrowingAction a) {
        try {
            a.run();
            api.info("codec: hook group ready: " + name);
        } catch (Throwable t) {
            api.error("codec: hook group unavailable: " + name, t);
        }
    }

    private interface ThrowingAction {
        void run() throws Throwable;
    }

    /** hook 返回 fnval 的静态方法（int c() 与 long d()）。
     *  6.3.0: FG1.b；6.4.0: 类迁到 gI1.e（方法名 c/d 未变）。 */
    private void installFnval() throws Throwable {
        Class<?> fg1b = null;
        String fnvalClsUsed = null;
        for (String cn : new String[]{"FG1.b", "GI1.e"}) {
            try {
                Class<?> c = api.load(cl, cn);
                boolean ok = true;
                try { api.declaredMethod(c, "c"); } catch (Throwable t2) { ok = false; }
                try { api.declaredMethod(c, "d"); } catch (Throwable t2) { ok = ok; }
                if (ok) { fg1b = c; fnvalClsUsed = cn; break; }
            } catch (Throwable next) {
                // 下一候选
            }
        }
        if (fg1b == null) {
            api.warn("codec: fnval class not found (FG1.b / gI1.e)");
            return;
        }
        // int fnval
        Method c = null;
        try {
            c = api.declaredMethod(fg1b, "c");
        } catch (NoSuchMethodException e) {
            api.warn("codec: FG1.b.c() not found");
        }
        if (c != null) {
            api.deoptimize(c);
            api.addHook("codec: fnval int", c, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    if (!(result instanceof Integer)) return result;
                    int v = ((Integer) result).intValue();
                    int nv = applyFnvalBits(v);
                    if (nv != v && probeFnval.compareAndSet(false, true)) {
                        api.info("codec: fnval int " + v + " -> " + nv
                                + " (codec=" + api.getCodecMode() + " audio=" + api.getAudioQuality()
                                + " hdr=" + api.getHdrMode() + ")");
                    }
                    return nv != v ? Integer.valueOf(nv) : result;
                }
            });
        }
        // long soft fnval
        Method d = null;
        try {
            d = api.declaredMethod(fg1b, "d");
        } catch (NoSuchMethodException e) {
            api.warn("codec: FG1.b.d() not found");
        }
        if (d != null) {
            api.deoptimize(d);
            api.addHook("codec: fnval long", d, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    if (!(result instanceof Long)) return result;
                    long v = ((Long) result).longValue();
                    long nv = applySoftFnvalBits(v);
                    if (nv != v && probeFnval.compareAndSet(false, true)) {
                        api.info("codec: fnval long " + v + " -> " + nv);
                    }
                    return nv != v ? Long.valueOf(nv) : result;
                }
            });
        }
        api.info("codec: fnval hook ok -> " + fnvalClsUsed + ".c()/d()");
    }

    /** 自动顺位：仅请求设备能硬解的编码位（都无硬解=仅 H264，不替换解码）；锁定：只开对应位。 */
    private int applyFnvalBits(int v) {
        int nv = v | FNVAL_DASH;
        int codec = api.getCodecMode();
        int audio = api.getAudioQuality();
        int hdr = api.getHdrMode();
        // HDR 位：自动顺位按设备能力（原逻辑已按 z5/z6 决定），锁定/关闭时强制
        if (hdr == 1) {            // 锁定 HDR（不含 Vivid）
            nv |= FNVAL_HDR;
            nv &= ~FNVAL_HDR_VIVID;
        } else if (hdr == 2) {     // 锁定 HDR Vivid（含 HDR 基础位）
            nv |= FNVAL_HDR | FNVAL_HDR_VIVID;
        } else if (hdr == 3) {     // 关闭 HDR
            nv &= ~(FNVAL_HDR | FNVAL_HDR_VIVID);
        }
        switch (codec) {
            case 1: // 锁定 HEVC（用户显式选择，不过滤，只提示风险）
                warnLockedNoHwOnce("HEVC", CodecCapability.hwHevc());
                nv |= FNVAL_H265;
                break;
            case 2: // 锁定 AV1
                warnLockedNoHwOnce("AV1", CodecCapability.hwAv1());
                nv |= FNVAL_AV1;
                break;
            default: // 自动顺位：无硬解的编码不向服务端请求（黑屏修复 v1.6.1）
                boolean hevcHw = CodecCapability.hwHevc();
                boolean av1Hw = CodecCapability.hwAv1();
                logHwCapOnce(hevcHw, av1Hw);
                if (api.isCodecHwFilterEnabled()) {
                    if (av1Hw) nv |= FNVAL_AV1;
                    if (hevcHw) nv |= FNVAL_H265;
                } else {
                    nv |= FNVAL_AV1 | FNVAL_H265;
                }
                break;
        }
        switch (audio) {
            case 2: // 杜比全景声
                nv |= FNVAL_DOLBY;
                break;
            case 3: // Hi-Res 无损
                nv |= FNVAL_DOLBY | 0x1000; // Dolby + 无损位(0x1000)
                break;
            default: // 自动顺位：杜比优先，其次无损
                nv |= FNVAL_DOLBY | 0x1000;
                break;
        }
        return nv;
    }

    private long applySoftFnvalBits(long v) {
        long nv = v;
        int codec = api.getCodecMode();
        if (codec == 1) {
            nv |= FNVAL_H265;
            return nv;
        }
        if (codec == 2) {
            nv |= FNVAL_AV1;
            return nv;
        }
        if (!api.isCodecHwFilterEnabled()) {
            nv |= FNVAL_AV1 | FNVAL_H265;
            return nv;
        }
        if (CodecCapability.hwAv1()) nv |= FNVAL_AV1;
        if (CodecCapability.hwHevc()) nv |= FNVAL_H265;
        return nv;
    }

    /** hook GeminiCommonResolverParams.c()：返回 VideoCodecType，实现顺位/锁定。 */
    private void installCodecPreference() throws Throwable {
        final Class<?> params = api.load(cl, "com.bilibili.app.gemini.base.player.GeminiCommonResolverParams");
        final Class<?> vct = api.load(cl, "tv.danmaku.ijk.media.player.IjkMediaAsset$VideoCodecType");
        final Object av1 = enumValue(vct, "AV1");
        final Object h265 = enumValue(vct, "H265");
        final Object h264 = enumValue(vct, "H264");
        final Object unknown = enumValue(vct, "UNKNOWN");
        final Field yField = api.declaredField(params, "y");
        final Method c = api.declaredMethod(params, "c");
        api.deoptimize(c);
        api.addHook("codec: preference", c, new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                int codec = api.getCodecMode();
                if (codec == 0) return chain.proceed(); // 自动：交给原逻辑（硬解过滤只在请求位做，不替换解码）
                Object thiz = chain.getThisObject();
                if (thiz == null) return chain.proceed();
                Object cur = yField.get(thiz);
                int curCodec = cur instanceof Integer ? ((Integer) cur).intValue() : 0;
                Object result = chain.proceed();
                if (codec == 2 && av1 != null && curCodec == CODECID_AV1) {
                    return av1;
                } else if (codec == 1 && h265 != null && curCodec == CODECID_HEVC) {
                    return h265;
                } else if (codec == 2 && av1 != null) {
                    // 锁定 AV1：结果若不是 AV1 且原 codecid 是 AV1 已处理；这里是兜底
                    return av1;
                } else if (codec == 1 && h265 != null) {
                    return h265;
                }
                return result;
            }
        });
        api.info("codec: codec preference hook ok -> GeminiCommonResolverParams.c()");
    }

    private void logHwCapOnce(boolean hevcHw, boolean av1Hw) {
        if (hwCapLogged.compareAndSet(false, true)) {
            api.info("codec: hw decode capability hevc=" + hevcHw + " av1=" + av1Hw
                    + " filter=" + api.isCodecHwFilterEnabled()
                    + " -> auto allows: " + (av1Hw ? "AV1 " : "")
                    + (hevcHw ? "HEVC" : (av1Hw ? "" : "none (H264 only)")));
        }
    }

    private void warnLockedNoHwOnce(String name, boolean hwOk) {
        if (!hwOk && hwCapLogged.compareAndSet(false, true)) {
            api.warn("codec: device has no " + name
                    + " hw decoder but codec is LOCKED to it — software-decode/black-screen"
                    + " risk (explicit user override, not filtered)");
        }
    }

    /** hook MediaResource.I(int,int)：默认音轨顺位 杜比 > Hi-Res > AAC。 */
    private void installAudioDefault() throws Throwable {
        final Class<?> mr = api.load(cl, "com.bilibili.lib.media.resource.MediaResource");
        final Method m = api.declaredMethod(mr, "I", int.class, int.class);
        api.deoptimize(m);
        api.addHook("codec: audio default", m, new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                int audio = api.getAudioQuality();
                if (audio == 0) return chain.proceed(); // 自动：保守不干预
                Object thiz = chain.getThisObject();
                if (thiz == null) return chain.proceed();
                Object arg0 = chain.getArg(0);
                if (audio == 2) { // 锁定杜比
                    Integer id = firstAudioIdOf(thiz, "l"); // l = Dolby
                    if (id != null) {
                        return chain.proceed(new Object[]{arg0, id});
                    }
                } else if (audio == 3) { // 锁定 Hi-Res 无损
                    Integer id = firstAudioIdOf(thiz, "m"); // m = Hi-Res
                    if (id == null) id = firstAudioIdOf(thiz, "l"); // 降级杜比
                    if (id != null) {
                        return chain.proceed(new Object[]{arg0, id});
                    }
                } else if (audio == 1) { // 锁定 AAC：保持默认
                    return chain.proceed(new Object[]{arg0, Integer.valueOf(0)});
                }
                return chain.proceed();
            }
        });
        api.info("codec: audio default hook ok -> MediaResource.I(int,int)");
    }

    /** 从 MediaResource 的 AudioEnhancementResource 字段取第一个音频流 id。 */
    private Integer firstAudioIdOf(Object mediaResource, String field) {
        try {
            Object enh = getFieldValue(mediaResource, field);
            if (enh == null) return null;
            Object list = getFieldValue(enh, "b");
            if (!(list instanceof List)) return null;
            List<?> items = (List<?>) list;
            if (items.isEmpty()) return null;
            Object first = items.get(0);
            if (first == null) return null;
            Object id = getFieldValue(first, "a");
            return id instanceof Number ? Integer.valueOf(((Number) id).intValue()) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object getFieldValue(Object obj, String name) {
        try {
            Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object enumValue(Class<?> enumCls, String name) {
        try {
            for (Object c : enumCls.getEnumConstants()) {
                if (c != null && name.equals(((java.lang.Enum<?>) c).name())) {
                    return c;
                }
            }
        } catch (Throwable t) { /* ignore */ }
        return null;
    }
}
