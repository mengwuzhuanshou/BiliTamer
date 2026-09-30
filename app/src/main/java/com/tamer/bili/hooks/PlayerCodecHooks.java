package com.tamer.bili.hooks;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * 播放器解码（HEVC / AV1 / H264）与音质（Hi-Res 无损 / 杜比全景声 / AAC）选择。
 *
 * 解码控制按设备硬解能力过滤<b>请求位</b>（CodecCapability，v1.6.1 引入、v1.7.1
 * 修复生效）：fnval 的 AV1/HEVC 位必须<b>显式按设置置位与清位</b>——B 站自身
 * （GI1.e.c()）会按自家能力检测（乐观判断，OEM 解码器运行时可失败）预先置好
 * AV1/HEVC 位，只 OR 不清位等于不过滤（v1.6.1 黑屏未修复根因，v1.7.1 起自动
 * 顺位+硬解过滤会把设备解不了的编码位从 fnval 中移除，服务端即不下发对应流）。
 * 各档位语义：自动=按硬解能力请求；锁定 HEVC/AV1/H264=只保留对应位（H264 锁定
 * 即清掉 AV1/HEVC/H266 位，只请求 H264）；关闭=不触碰解码位（纯 App 行为）。
 * 只过滤请求、不替换解码——自动时的顺位选择仍完全交给原逻辑在实发流集合上
 * 自行回退，锁定项是用户显式选择也不过滤（仅告警）。
 *
 * 6.3.0 落点：
 *  - fnval 位控制服务端下发哪些格式的流。hook FG1.b 的 fnval 计算（int c() 与
 *    long d()）；6.4.0 类迁到 GI1.e（方法名 c/d 未变，9100300 实测）。
 *  - 视频解码偏好：GeminiCommonResolverParams.c() 返回 VideoCodecType（codecid
 *    字段 7=AVC, 12=HEVC, 13=AV1；字段名 6.3.0 为 y、6.4.0 漂移为 z，按序探测）。
 *    hook 该方法实现顺位/锁定。
 *  - 音质顺位：MediaResource.I(int,int) 构建 IjkMediaAsset 时选择默认音轨 id。
 *    杜比(DOLBY) 与 Hi-Res(HIRES) 音频流以 AudioEnhancementResource 挂在
 *    mediaResource.l / mediaResource.m。hook I() 把默认音轨指到顺位首个可用项。
 */
public final class PlayerCodecHooks {

    // fnval 位定义（B 站播放器共用约定；9100300 GI1.e.c() 实测核对）
    private static final int FNVAL_DASH  = 0x10;       // 16
    private static final int FNVAL_HDR   = 0x40;       // 64    HDR
    private static final int FNVAL_DOLBY = 0x80;       // 128   Dolby 音频
    private static final int FNVAL_AV1   = 0x200;      // 512   AV1 视频
    private static final int FNVAL_AV1_SOFT = 0x800;   // 2048  AV1 软解支持位（与 AV1 位联动置/清）
    private static final int FNVAL_LOSSLESS = 0x1000;  // 4096  Hi-Res 无损音频
    private static final int FNVAL_HDR_VIVID = 0x4000; // 16384 HDR Vivid
    private static final int FNVAL_H265  = 0x10000;    // 65536 HEVC/H266 相关位（9100300 中
                                                       // H266 realtime 逻辑与 HEVC 共用此位）

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

    /** fnval 持有者形状校验：a()Z / b()Z / c()I / d()J 四个无参方法齐备，
     *  且有「自身类型的 static 字段（单例）」+ static int + static long 两个缓存位。
     *  这套特征在 6.5.0(kJ1.a) 与 6.6.0(aK1.a) 上全 dex 唯一命中（shapesearch 实测），
     *  足以把 R8 复用同名的无关类挡在外面。
     *  坑：这里比对的是反射 Class#getSimpleName()（boolean/int/long），
     *  不是 dex 描述符 Z/I/J —— 用 Z/I/J 会比中不了，等于形状校验永远判死。 */
    private static boolean isFnvalHolder(Class<?> c) {
        String[][] req = {{"a", "boolean"}, {"b", "boolean"}, {"c", "int"}, {"d", "long"}};
        for (String[] r : req) {
            boolean hit = false;
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(r[0]) || m.getParameterTypes().length != 0) continue;
                if (m.getReturnType().getName().equals(r[1])) { hit = true; break; }
            }
            if (!hit) return false;
        }
        boolean selfSingleton = false;
        boolean cacheI = false;
        boolean cacheJ = false;
        for (java.lang.reflect.Field f : c.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            Class<?> t = f.getType();
            if (t == c) selfSingleton = true;
            else if (t == int.class) cacheI = true;
            else if (t == long.class) cacheJ = true;
        }
        return selfSingleton && cacheI && cacheJ;
    }

    /** 形状不符时给出「缺哪一条」，一条日志就能判断是类名复用还是方法漂移。 */
    private static String fnvalShapeMiss(Class<?> c) {
        StringBuilder miss = new StringBuilder();
        String[][] req = {{"a", "boolean"}, {"b", "boolean"}, {"c", "int"}, {"d", "long"}};
        java.util.Set<String> have = new java.util.HashSet<>();
        for (Method m : c.getDeclaredMethods()) {
            if (m.getParameterTypes().length == 0) {
                have.add(m.getName() + m.getReturnType().getSimpleName());
            }
        }
        for (String[] r : req) {
            String want = r[0] + r[1];
            if (!have.contains(want)) {
                miss.append("no ").append(want).append(' ');
            }
        }
        boolean selfSingleton = false;
        boolean cacheI = false;
        boolean cacheJ = false;
        for (java.lang.reflect.Field f : c.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            Class<?> t = f.getType();
            if (t == c) selfSingleton = true;
            else if (t == int.class) cacheI = true;
            else if (t == long.class) cacheJ = true;
        }
        if (!selfSingleton) miss.append("no-self-singleton ");
        if (!cacheI) miss.append("no-static-I ");
        if (!cacheJ) miss.append("no-static-J ");
        miss.append("(methods=").append(c.getDeclaredMethods().length)
                .append(",fields=").append(c.getDeclaredFields().length).append(')');
        return miss.toString().trim();
    }

    /** hook 返回 fnval 的类方法（int c() 与 long d()，direct private 实例方法，
     *  经单例字段 a 调用）。6.3.0: FG1.b；6.4.0: GI1.e；6.5.0: kJ1.a；6.6.0: aK1.a。
     *  四类同构：单例 a、I 缓存 c、J 缓存 d、a()Z/b()Z 懒加载能力位、c()I/d()J。
     *  （6.5.0 jadx 实证 c() 仍含 512/2048/65536 fnval 位运算。）
     *  6.6.0 定位置证（按形状全 dex 反查，两版都唯一命中）：
     *  6.5.0 命中 kJ1.a（即当年人工记录的类名，说明形状规格可信），6.6.0 唯一命中 aK1.a；
     *  同时 6.6.0 的 FG1.b / GI1.e / kJ1.a 已被 R8 复用成无关类（菜单工具 / Runnable /
     *  lazy 持有者）——所以候选必须过 isFnvalHolder 形状校验，光看类名会把 hook
     *  挂到无关方法上（PITFALLS #16）。aK1.a.d() 里调 IjkCodecHelper.isH266SupportSoft，
     *  c() 里调 IjkCpuInfo.getCpuName，与 soft_fnval/fnval 职责一致。 */
    private void installFnval() throws Throwable {
        Class<?> fg1b = null;
        String fnvalClsUsed = null;
        StringBuilder why = new StringBuilder();
        for (String cn : new String[]{"aK1.a", "kJ1.a", "GI1.e", "FG1.b"}) {
            try {
                Class<?> c = api.load(cl, cn);
                if (isFnvalHolder(c)) { fg1b = c; fnvalClsUsed = cn; break; }
                why.append(cn).append("=shape(").append(fnvalShapeMiss(c)).append(") ");
            } catch (Throwable next) {
                // 下一候选：把失败原因带进日志，别让它停在「类名没找到」这种猜测上
                why.append(cn).append("=").append(next.getClass().getSimpleName())
                        .append("(").append(next.getMessage()).append(") ");
            }
        }
        if (fg1b == null) {
            api.warn("codec: fnval class not found (aK1.a / kJ1.a / GI1.e / FG1.b)"
                    + " loader=" + cl + " why: " + why);
            return;
        }
        // int fnval
        Method c = null;
        try {
            c = api.declaredMethod(fg1b, "c");
        } catch (NoSuchMethodException e) {
            api.warn("codec: " + fnvalClsUsed + ".c() not found");
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

    /**
     * int fnval 位改写（v1.7.1 重写）。
     *
     * <b>黑屏修复要点</b>：B 站自身 fnval 计算（9100300 为 GI1.e.c()）会按自家
     * 能力检测预先置 AV1(0x200)/AV1软解(0x800)/HEVC·H266(0x10000) 位；v1.6.1 及
     * 之前只在原值上 OR 加位、从不清位，自家已置的位永远留着——过滤形同虚设，
     * OEM 硬解运行时失败的流照样下发，黑屏依旧。这里改为<b>先按设置显式清位
     * 再置位</b>：关闭/锁 H264/自动无硬解时把对应位从请求中移除，服务端才真
     * 的不下发。其余位（4K/帧率/平台等）不动。
     *
     * 档位：codec 0=自动(硬解过滤) 1=锁 HEVC 2=锁 AV1 3=锁 H264 4=关闭(不干预)；
     * hdr 0=自动 1=锁 HDR 2=锁 Vivid 3=强制关 4=关闭(不干预)；
     * audio 0/4=不干预音质位 2=锁杜比 3=锁无损（无损含杜比位）。
     */
    private int applyFnvalBits(int v) {
        int nv = v | FNVAL_DASH;
        int codec = api.getCodecMode();
        int audio = api.getAudioQuality();
        int hdr = api.getHdrMode();

        // ---- 解码位：先清后设（清位是 v1.7.1 修复核心）----
        int av1Bits = FNVAL_AV1 | FNVAL_AV1_SOFT;
        switch (codec) {
            case 1: // 锁定 HEVC（用户显式选择，不过滤，只提示风险）
                warnLockedNoHwOnce("HEVC", CodecCapability.hwHevc());
                nv &= ~av1Bits;
                nv |= FNVAL_H265;
                break;
            case 2: // 锁定 AV1
                warnLockedNoHwOnce("AV1", CodecCapability.hwAv1());
                nv &= ~FNVAL_H265;
                nv |= av1Bits;
                break;
            case 3: // 锁定 H264：只请求 H264（清 AV1/HEVC/H266 全部高位）
                nv &= ~(av1Bits | FNVAL_H265);
                break;
            case 4: // 关闭：解码位完全交给 App 原逻辑，不触碰
                break;
            default: // 0 自动顺位：无硬解的编码不向服务端请求（黑屏修复）
                boolean hevcHw = CodecCapability.hwHevc();
                boolean av1Hw = CodecCapability.hwAv1();
                logHwCapOnce(hevcHw, av1Hw);
                if (api.isCodecHwFilterEnabled()) {
                    // 有硬解的编码保留/置位，没有的清掉（顺位择优交给 App 原逻辑）
                    if (av1Hw) { nv |= av1Bits; } else { nv &= ~av1Bits; }
                    if (hevcHw) { nv |= FNVAL_H265; } else { nv &= ~FNVAL_H265; }
                } else {
                    // 过滤关闭=旧行为：AV1/HEVC 都请求
                    nv |= av1Bits | FNVAL_H265;
                }
                break;
        }
        // ---- HDR 位 ----
        switch (hdr) {
            case 1: // 锁定 HDR（不含 Vivid）
                nv |= FNVAL_HDR;
                nv &= ~FNVAL_HDR_VIVID;
                break;
            case 2: // 锁定 HDR Vivid（含 HDR 基础位）
                nv |= FNVAL_HDR | FNVAL_HDR_VIVID;
                break;
            case 3: // 强制关闭 HDR
                nv &= ~(FNVAL_HDR | FNVAL_HDR_VIVID);
                break;
            default: // 0 自动 / 4 关闭：不触碰 HDR 位（App 原逻辑已按设备能力决定）
                break;
        }
        // ---- 音质位 ----
        switch (audio) {
            case 2: // 锁定杜比全景声
                nv &= ~FNVAL_LOSSLESS;
                nv |= FNVAL_DOLBY;
                break;
            case 3: // 锁定 Hi-Res 无损（含杜比位）
                nv |= FNVAL_DOLBY | FNVAL_LOSSLESS;
                break;
            default: // 0 自动 / 4 关闭：不触碰音质位
                break;
        }
        return nv;
    }

    /**
     * long soft_fnval 位改写。9100300 GI1.e.d() 位表：bit0=AV1 软解支持、
     * bit1=HEVC/H266 软解支持。与 int fnval 同策略：先清后设，关闭=不触碰。
     */
    private long applySoftFnvalBits(long v) {
        long nv = v;
        int codec = api.getCodecMode();
        if (codec == 3) {          // 锁 H264：清软解请求位
            return nv & ~(1L | 2L);
        }
        if (codec == 4) {          // 关闭：不干预
            return nv;
        }
        if (codec == 1) {          // 锁 HEVC
            return (nv & ~1L) | 2L;
        }
        if (codec == 2) {          // 锁 AV1
            return (nv & ~2L) | 1L;
        }
        if (!api.isCodecHwFilterEnabled()) {
            return nv | 1L | 2L;   // 过滤关闭=旧行为
        }
        // 自动：按硬解能力置位（无硬解则清掉对应软解位）
        long av1 = CodecCapability.hwAv1() ? 1L : 0L;
        long hevc = CodecCapability.hwHevc() ? 2L : 0L;
        return (nv & ~(1L | 2L)) | av1 | hevc;
    }

    /**
     * hook GeminiCommonResolverParams.c()：返回 VideoCodecType，实现锁定。
     * 自动/关闭档位不干预（硬解过滤只在 fnval 请求位做，不替换解码选择）。
     */
    private void installCodecPreference() throws Throwable {
        final Class<?> params = api.load(cl, "com.bilibili.app.gemini.base.player.GeminiCommonResolverParams");
        final Class<?> vct = api.load(cl, "tv.danmaku.ijk.media.player.IjkMediaAsset$VideoCodecType");
        final Object av1 = enumValue(vct, "AV1");
        final Object h265 = enumValue(vct, "H265");
        final Object h264 = enumValue(vct, "H264");
        // codecid 字段名漂移：6.3.0 为 y，6.4.0(9100300) 实测漂移为 z（y 变常量 2）。
        // 按序探测，谁在取谁；都拿不到则锁定模式退化为不干预（fnval 位仍是主机制）。
        final Field codecidField = declaredFieldTry(params, "z", "y");
        if (codecidField == null) {
            api.warn("codec: codecid field not found (z/y) — preference lock degraded");
        }
        final Method c = api.declaredMethod(params, "c");
        api.deoptimize(c);
        api.addHook("codec: preference", c, new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                int codec = api.getCodecMode();
                if (codec == 0 || codec == 4) return chain.proceed(); // 自动/关闭：交给原逻辑
                Object thiz = chain.getThisObject();
                if (thiz == null) return chain.proceed();
                int curCodec = 0;
                if (codecidField != null) {
                    try {
                        Object cur = codecidField.get(thiz);
                        curCodec = cur instanceof Integer ? ((Integer) cur).intValue() : 0;
                    } catch (Throwable ignored) {
                    }
                }
                Object result = chain.proceed();
                if (codec == 3 && h264 != null) {
                    // 锁 H264：实发流是 H264 时确认返回 H264（与 fnval 清位互补）
                    if (curCodec == CODECID_AVC) {
                        return h264;
                    }
                } else if (codec == 2 && av1 != null) {
                    if (curCodec == CODECID_AV1) {
                        return av1;
                    }
                } else if (codec == 1 && h265 != null) {
                    if (curCodec == CODECID_HEVC) {
                        return h265;
                    }
                }
                return result;
            }
        });
        api.info("codec: codec preference hook ok -> GeminiCommonResolverParams.c()");
    }

    /** 按序探测声明字段（混淆名跨构建漂移兜底），全部缺失返回 null。 */
    private Field declaredFieldTry(Class<?> cls, String... names) {
        for (int i = 0; i < names.length; i++) {
            try {
                return api.declaredField(cls, names[i]);
            } catch (Throwable ignored) {
            }
        }
        return null;
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
                if (audio == 0 || audio == 4) return chain.proceed(); // 自动/关闭：不干预
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
