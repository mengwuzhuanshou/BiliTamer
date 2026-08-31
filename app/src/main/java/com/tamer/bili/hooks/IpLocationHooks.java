package com.tamer.bili.hooks;

import com.tamer.bili.BiliConfig;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;

/**
 * 评论区与用户主页 IP 属地显示。
 *
 * 手法：B 站服务端按请求身份(mobi_app/build/channel/appId)决定是否返回 IP 属地字段。
 * 国际版客户端默认以 android_i 身份请求，服务端不返回 location；本模块把请求身份
 * 改写为国内版 android_hd/2001100/master(appId=5, version=2.0.1)，服务端即返回
 * location 字段，而国际版 UI 已内置 IP 属地渲染。
 *
 * 6.3.0 落点（真实类名，jadx deobfuscation 会显示为 p488mq0.a / p061ip1.h）：
 *  - KMP moss gRPC（评论 Reply/ DmView 等走 KMossServiceImp -> ip1.h.a）：身份头
 *    x-bili-metadata-bin / x-bili-device-bin 由 up1.a.a()（KMetadata/KDevice 提供者）
 *    生成。hook up1.a.a() 在返回的 jp1.c(key, byte[]) 上直接改写 protobuf 字节中的
 *    mobiApp（android_i -> android_hd，含长度前缀重建）。
 *  - 旧 moss / REST 路径：mq0.a.e()(Metadata) / d()(Device) 生成身份头，经 okhttp
 *    Aq0.a 注入；同步改写。
 */
public final class IpLocationHooks {
    // 国内版评论客户端身份（与国内版 HD 一致）
    private static final String MOBI_APP = "android_hd";
    private static final int BUILD = 2001100;
    private static final String CHANNEL = "master";
    private static final int APP_ID = 5;
    private static final String VERSION_NAME = "2.0.1";

    // 评论 RPC 服务与方法
    private static final String REPLY_SERVICE = "bilibili.main.community.reply.v1";
    private static final String[] REPLY_METHODS = {
        "MainList", "DetailList", "DialogList", "PreviewList", "ReplyInfo",
        "SearchItem", "SearchItemPreHook", "ShareRepliesInfo", "FoldList", "HotspotPage"
    };

    // MossCommonHeadersProvider（kntr.base.moss.ignet.impl.header.j）
    private static final String COMMON_HEADERS_CLS = "kntr.base.moss.ignet.impl.header.b";

    // 探针：j.a 观察到的服务名（每服务名只记一次，上限防刷屏）
    private final java.util.Set<String> seenServices =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<String>());

    private static final long RETRY_DELAY_MS = 500L;
    private static final int MAX_RETRY = 30;

    private final HookApi api;
    private final ClassLoader cl;

    private final AtomicBoolean mossScopeReady = new AtomicBoolean(false);
    private final AtomicInteger mossScopeAttempts = new AtomicInteger(0);
    private final AtomicBoolean identityReady = new AtomicBoolean(false);
    private final AtomicInteger identityAttempts = new AtomicInteger(0);
    private final AtomicBoolean kmpHeaderReady = new AtomicBoolean(false);
    private final AtomicInteger kmpHeaderAttempts = new AtomicInteger(0);

    private final AtomicInteger restParamsAttempts = new AtomicInteger(0);

    private final java.util.concurrent.atomic.AtomicBoolean probeKmpEntry = new java.util.concurrent.atomic.AtomicBoolean(false);

    private final java.util.concurrent.atomic.AtomicBoolean probeIdpFire = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.Set<String> urlProbeSeen =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private final java.util.concurrent.atomic.AtomicLong mossRpcCount =
            new java.util.concurrent.atomic.AtomicLong(0);

    private final java.util.Set<String> seenActivities =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private final java.util.concurrent.atomic.AtomicBoolean spaceParamFired =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    // 运行时探针：verbose 关闭时，每类改写的第一条必打一行（无 logcat 也能确认活体）
    private final AtomicBoolean probeKmp = new AtomicBoolean(false);
    private final AtomicBoolean probeCommon = new AtomicBoolean(false);
    private final AtomicBoolean probeIdp = new AtomicBoolean(false);
    private final AtomicBoolean probeRest = new AtomicBoolean(false);

    private static final ThreadLocal<String> sScope = new ThreadLocal<String>();
    /** 评论区限定模式：moss-common-headers 拦截器在 proceed 前设置的本次 RPC 服务名。
     *  up1.a.a() 在该拦截器内部被同步调用（同线程），凭此标记精确改写。 */
    private static final ThreadLocal<String> sCommonScope = new ThreadLocal<String>();

    /** 空间页 UI 定域：页面打开后的时间窗（毫秒时间戳），窗口内 kr1.a.a 全部改写。
     *  6.4.0 空间 REST 走 kntr 直连 provider，不经过 header.b，svc 无法定位，只能按 UI 定位。 */
    private static volatile long sUiSpaceUntil = 0L;

    public IpLocationHooks(HookApi api, ClassLoader cl) {
        this.api = api;
        this.cl = cl;
    }

    public void install() {
        installGroup("rest identity", new ThrowingAction() {
            @Override public void run() throws Throwable {
                installRest();
            }
        });
        // moss 部分：立即尝试，失败则延迟重试
        installMossScope();
        installIdentityProvider();
        installKmpHeaderValue();
        installRestParams();
        installCommonHeadersScope();
        api.info("IpLocationHooks installed");
    }

    private void installGroup(String name, ThrowingAction a) {
        try {
            a.run();
            api.info("ip: hook group ready: " + name);
        } catch (Throwable t) {
            api.error("ip: hook group unavailable: " + name, t);
        }
    }

    private interface ThrowingAction {
        void run() throws Throwable;
    }

    /** 标记当前线程为评论 RPC scope（KMP moss 发送入口 ip1.h.a）。 */
    private void installMossScope() {
        if (mossScopeReady.get()) return;
        if (mossScopeAttempts.incrementAndGet() > MAX_RETRY) {
            api.warn("ip: moss scope give up after " + MAX_RETRY + " attempts");
            return;
        }
        try {
            final Class<?> ip1h = api.load(cl, "ip1.h");
            Method m = null;
            for (Method mm : ip1h.getDeclaredMethods()) {
                if (mm.getName().equals("a") && mm.getParameterTypes().length == 4) {
                    m = mm;
                    break;
                }
            }
            if (m == null) {
                throw new NoSuchMethodException("ip1.h.a(4-arg) not found");
            }
            api.deoptimize(m);
            api.addHook("ip: moss scope", m, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object g = chain.getArg(0);
                    if (g == null) return chain.proceed();
                    String svc = strField(g, "a");
                    String method = strField(g, "c");
                    if (svc != null && REPLY_SERVICE.equals(svc) && isReplyMethod(method)) {
                        String old = sScope.get();
                        if (old == null) {
                            sScope.set("rpc:" + method);
                            try {
                                return chain.proceed();
                            } finally {
                                sScope.remove();
                            }
                        } else {
                            try {
                                return chain.proceed();
                            } finally {
                                sScope.set(old);
                            }
                        }
                    }
                    return chain.proceed();
                }
            });
            mossScopeReady.set(true);
            api.info("ip: moss scope hook ok -> " + ip1h.getName() + ".a (attempt=" + mossScopeAttempts.get() + ")");
        } catch (ClassNotFoundException e) {
            api.debug("ip: moss scope class not loaded yet, retry in " + RETRY_DELAY_MS + "ms");
            retry(new Runnable() {
                @Override public void run() {
                    installMossScope();
                }
            });
        } catch (Throwable t) {
            api.error("ip: moss scope install failed", t);
        }
    }

    /** KMP KMetadata/KDevice 头提供者：up1.a.a() 返回 jp1.c(key, byte[])。
     *  这是评论 gRPC 的 x-bili-metadata-bin / x-bili-device-bin 实际来源。
     *  hook 后在返回的字节上改写 mobiApp（android_i -> android_hd）。 */
    private void installKmpHeaderValue() {
        if (kmpHeaderReady.get()) return;
        if (kmpHeaderAttempts.incrementAndGet() > MAX_RETRY) {
            api.warn("ip: kmp header value give up after " + MAX_RETRY + " attempts");
            return;
        }
        // 6.3.0: up1.a.a() -> jp1.c(key, byte[])；6.4.0: 基类 kr1.a.a() -> Zq1.c(key, byte[])
        // （子类 nr1.a=KMetadata / mr1.a=KDevice，a() 为 final 基类方法，hook 一处覆盖两者）。
        // hooker 按字段扫描 String key + byte[] value，对两种包装类通用。
        Class<?> cls = null;
        String clsUsed = null;
        for (String cn : new String[]{"up1.a", "kr1.a"}) {
            try {
                Class<?> c = api.load(cl, cn);
                api.declaredMethod(c, "a"); // 确认形状
                cls = c;
                clsUsed = cn;
                break;
            } catch (Throwable next) {
                // 下一候选
            }
        }
        if (cls == null) {
            if (kmpHeaderAttempts.get() >= MAX_RETRY) {
                api.warn("ip: kmp header value give up (no provider candidate found)");
            } else {
                api.debug("ip: kmp header providers not present yet, retry in " + RETRY_DELAY_MS + "ms");
                retry(new Runnable() {
                    @Override public void run() {
                        installKmpHeaderValue();
                    }
                });
            }
            return;
        }
        try {
            final Method m = api.declaredMethod(cls, "a");
            api.deoptimize(m);
            api.addHook("ip: kmp header value", m, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    if (!api.isIpLocationEnabled()) return result;
                    if (api.getIpScopeMode() == BiliConfig.IP_SCOPE_COMMENT) {
                        // 评论区限定模式：仅当本次 RPC 是评论/字幕服务时改写
                        if (sCommonScope.get() == null) return result;
                    }
                    // 空间页 UI 定域：窗口内放行（6.4.0 空间通道不经过 header.b，无法按 svc 定位）
                    if (sCommonScope.get() == null) {
                        if (System.currentTimeMillis() >= sUiSpaceUntil) return result;
                        if (probeKmpEntry.compareAndSet(false, true)) {
                            api.info("ip: kmp hook fired (ui:space window)");
                        }
                    }
                    if (result == null) return result;
                    if (probeKmpEntry.compareAndSet(false, true)) {
                        String k0 = null; int len0 = -1; boolean hasOld = false;
                        try {
                            for (Field f0 : result.getClass().getDeclaredFields()) {
                                f0.setAccessible(true);
                                Object v = f0.get(result);
                                if (f0.getType() == String.class && k0 == null && v != null) k0 = String.valueOf(v);
                                if (f0.getType() == byte[].class && v instanceof byte[]) {
                                    len0 = ((byte[]) v).length;
                                    hasOld = indexOfBytes((byte[]) v, "android_i") >= 0;
                                }
                            }
                        } catch (Throwable ignore0) { }
                        api.info("ip: kmp hook fired key=" + k0 + " bytes=" + len0
                                + " containsAndroidI=" + hasOld + " scope=" + sCommonScope.get());
                    }
                    try {
                        String keyStr = null;
                        Field valF = null;
                        for (Field f : result.getClass().getDeclaredFields()) {
                            f.setAccessible(true);
                            if (f.getType() == String.class && keyStr == null) {
                                Object k = f.get(result);
                                keyStr = k == null ? null : String.valueOf(k);
                            } else if (f.getType() == byte[].class) {
                                valF = f;
                            }
                        }
                        if (keyStr != null && valF != null
                                && ("x-bili-metadata-bin".equals(keyStr) || "x-bili-device-bin".equals(keyStr))) {
                            Object val = valF.get(result);
                            if (val instanceof byte[]) {
                                byte[] src = (byte[]) val;
                                byte[] out = rewriteMobiAppBytes(src);
                                if (out != null) {
                                    valF.set(result, out);
                                    if (api.isVerboseLoggingEnabled()) {
                                        api.info("ip: kmp header value rewritten: " + keyStr
                                                + " (" + src.length + " -> " + out.length + " bytes)");
                                    } else {
                                        logRewrite(probeKmp, "kmp " + keyStr + " 改写生效");
                                    }
                                }
                            }
                        }
                    } catch (Throwable t) {
                        api.warn("ip: kmp header value rewrite failed: " + t);
                    }
                    return result;
                }
            });
            kmpHeaderReady.set(true);
            api.info("ip: kmp header value hook ok -> " + clsUsed + ".a (attempt=" + kmpHeaderAttempts.get() + ")");
        } catch (Throwable t) {
            api.error("ip: kmp header value hook failed", t);
        }
    }

    /** 评论区限定身份改写（v1.3，方向2 的正解）。
     *
     *  挂点：MossCommonHeadersProvider 拦截器（kntr.base.moss.ignet.impl.header.b，name=
     *  "moss-common-headers"，priority 0）。它是 GrpcEngine 拦截器链的一员，b(chain, cont)
     *  内部同步遍历 jp1.b/jp1.d 头提供者（含 up1.a -> x-bili-metadata-bin / x-bili-device-bin）
     *  把头写进本次调用上下文 grpc.c 的头存储（grpc.d.a=String 头 / d.b=byte[] 头），
     *  然后 chain.proceed() 交给后续拦截器。因此：
     *   - proceed 返回后头存储里必有本次请求的最终身份头（可改）；
     *   - chain.a() 即 grpc.c（继承 MossInterceptor.e，字段 b=jp1.g method 描述符），
     *     service/method 判定与改写同线程同帧，无跨线程问题；
     *   - 一元 RPC 走这里；stream tunnel 走 header.j（不受影响）。
     *
     *  方法为 suspend，可能在后续拦截器处挂起返回 COROUTINE_SUSPENDED——但头在本拦截器
     *  proceed 之前已入存储，两种返回形态下改写同样有效（重复进入幂等）。
     */
    private void installCommonHeadersScope() {
        try {
            final Class<?> cls = api.load(cl, COMMON_HEADERS_CLS);
            Method m = null;
            for (Method mm : cls.getDeclaredMethods()) {
                Class<?>[] ps = mm.getParameterTypes();
                if (mm.getName().equals("b") && ps.length == 2 && !mm.isSynthetic()) {
                    m = mm;
                    break;
                }
            }
            if (m == null) {
                throw new NoSuchMethodException(COMMON_HEADERS_CLS + ".b(2-arg) not found");
            }
            api.deoptimize(m);
            api.addHook("ip: common headers scope", m, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    // up1.a.a() 在本拦截器内部被同步调用：proceed 前设标记，改写才能赶在发请求前
                    long n = mossRpcCount.incrementAndGet();
                if (n % 200 == 1) {
                    api.info("ip: moss rpc count=" + n);
                }
                String want = computeWantedService(chain.getArg(0));
                    String old = sCommonScope.get();
                    if (want != null) sCommonScope.set(want);
                    try {
                        return chain.proceed();
                    } finally {
                        if (want != null) {
                            if (old == null) sCommonScope.remove(); else sCommonScope.set(old);
                        }
                    }
                }
            });
            api.info("ip: common headers scope hook ok -> " + cls.getName() + ".b");
        } catch (ClassNotFoundException e) {
            api.debug("ip: " + COMMON_HEADERS_CLS + " not loaded yet, retry in " + RETRY_DELAY_MS + "ms");
            retry(new Runnable() {
                @Override public void run() {
                    installCommonHeadersScope();
                }
            });
        } catch (Throwable t) {
            api.error("ip: common headers scope hook failed", t);
        }
    }

    /** 判定本次 RPC 是否需要改写身份：需要则返回服务名（作 ThreadLocal 标记），否则 null。
     *  在 chain.proceed() 之前调用；改写本身由 up1.a.a() hook 完成。 */
    private String computeWantedService(Object chainObj) {
        try {
            if (chainObj == null) return null;
            if (api.getIpScopeMode() != BiliConfig.IP_SCOPE_COMMENT) return null;
            boolean ip = api.isIpLocationEnabled();
            if (!ip) return null;
            // chain -> grpc.c 上下文（MossInterceptor.b.a()）
            Object ctx = callNoArg(chainObj, "a", "MossInterceptor$e");
            if (ctx == null) ctx = callNoArg(chainObj, "a", "ignet.impl.grpc.c");
            if (ctx == null) ctx = fieldInHierarchy(chainObj, "a");
            if (ctx == null) {
                logRewriteOnce("ctx", "ip: common headers ctx not found (chain=" + chainObj.getClass().getName() + ")");
                return null;
            }
            Object g = fieldTypedInHierarchy(ctx, "b", "Zq1.g");
            if (g == null) g = fieldTypedInHierarchy(ctx, "b", "jp1.g");
            // 6.3.0 jp1.g：service 在字段 a；6.4.0 Zq1.g(KMethodDescriptor)：
            // a=packageName, b=serviceName, c=methodName —— 两者都试，取像服务名的那个
            String svc = g == null ? null : strField(g, "b");
            String method = g == null ? null : strField(g, "c");
            if (!isReplyService(svc)) {
                String alt = strField(g, "a");
                if (isReplyService(alt)) svc = alt;
            }
            if (svc == null) {
                // 兜底：k 也有服务名字段
                Object k = fieldTypedInHierarchy(ctx, "a", "Zq1.k");
                if (k == null) k = fieldTypedInHierarchy(ctx, "a", "jp1.k");
                svc = k == null ? null : strField(k, "a");
            }
            if (svc == null && g != null) {
                logRewriteOnce("svc", "ip: method descriptor resolved but no service string (cls="
                        + g.getClass().getName() + ")");
            }
            String pkg = g == null ? null : strField(g, "a");
            rememberService(svc, pkg);
            // 评论区限定：评论区 + 空间页 + 主页（各按服务名/包名识别，不做全局声明）
            boolean want = ip && (isReplyService(svc) || isSpaceService(svc, pkg) || isHomeService(svc, pkg));
            if (!want) return null;
            if (api.isVerboseLoggingEnabled()) {
                api.info("ip: scoped rewrite armed svc=" + svc + " method=" + method);
            } else {
                logRewrite(probeCommon, "common-headers 评论区限定改写待生效: " + svc);
            }
            return svc;
        } catch (Throwable t) {
            api.warn("ip: computeWantedService failed: " + t);
            return null;
        }
    }

    /** 调无参方法，按返回类型名过滤歧义重载。 */
    private static Object callNoArg(Object obj, String name, String retHint) {
        if (obj == null) return null;
        try {
            for (Method mm : obj.getClass().getMethods()) {
                if (!mm.getName().equals(name)) continue;
                if (mm.getParameterTypes().length != 0) continue;
                String rt = mm.getReturnType().getName();
                if (retHint != null && !rt.contains(retHint) && !rt.replace("$", "$").contains(retHint)) continue;
                mm.setAccessible(true);
                return mm.invoke(obj);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 在类层级里按字段名+类型名找字段值。 */
    private static Object fieldTypedInHierarchy(Object obj, String name, String typeHint) {
        if (obj == null) return null;
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                if (typeHint == null || f.getType().getName().contains(typeHint)) {
                    return f.get(obj);
                }
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable ignored2) {
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static Object fieldInHierarchy(Object obj, String name) {
        return fieldTypedInHierarchy(obj, name, null);
    }

    /** 单个二进制身份头的改写（android_i -> android，长度前缀重建）。返回是否改写。 */
    private boolean rewriteHeaderEntry(java.util.Map map, String key) {
        try {
            Object v = map.get(key);
            if (!(v instanceof byte[])) {
                if (v != null) {
                    logRewriteOnce("hdrtype-" + key,
                            "ip: unexpected header value type for " + key + ": "
                                    + v.getClass().getName());
                }
                return false;
            }
            byte[] out = rewriteMobiAppBytes((byte[]) v);
            if (out != null) {
                map.put(key, out);
                return true;
            }
            return false;
        } catch (Throwable t) {
            api.warn("ip: rewriteHeaderEntry " + key + " failed: " + t);
            return false;
        }
    }

    private final java.util.Set<String> loggedHdrTypes =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** 同一问题每次进程只报一次，避免刷屏。 */
    private void logRewriteOnce(String key, String msg) {
        if (!loggedHdrTypes.add(key)) return;
        api.info(msg);
    }

    private void rememberService(String svc, String pkg) {
        if (svc == null || svc.length() == 0) return;
        String key = svc + "|" + (pkg == null ? "" : pkg);
        if (seenServices.size() >= 80) return;
        if (seenServices.add(key)) {
            api.debug("ip: moss svc=" + svc + " pkg=" + pkg);
        }
    }

    /** 评论区服务判定（按服务名，覆盖该服务全部方法）。 */
    private static boolean isReplyService(String svc) {
        if (svc == null) return false;
        return svc.equals(REPLY_SERVICE) || svc.startsWith("bilibili.main.community.reply");
    }

    /** 空间页服务判定（个人主页 IP 属地；REST 之外 6.4.0 可能走 moss）。 */
    private static boolean isSpaceService(String svc, String pkg) {
        String s = svc == null ? null : svc.toLowerCase();
        String p = pkg == null ? null : pkg.toLowerCase();
        if (s != null && (s.startsWith("bilibili.app.space") || s.contains(".space."))) return true;
        return p != null && (p.startsWith("bilibili.app.space") || p.contains(".space."));
    }

    /** 主页推荐服务判定（6.4.0 国际版首页 feed；限定武装，不做全局声明）。 */
    private static boolean isHomeService(String svc, String pkg) {
        String s = svc == null ? null : svc.toLowerCase();
        String p = pkg == null ? null : pkg.toLowerCase();
        if (s != null && (s.startsWith("bilibili.app.interfaces") || s.contains("pegasus"))) return true;
        return p != null && (p.contains("bilibili.app.interfaces") || p.contains("pegasus"));
    }

    /** 改写 Metadata/Device 身份头（旧 moss / REST 路径）。 */
    private void installIdentityProvider() {
        if (identityReady.get()) return;
        if (identityAttempts.incrementAndGet() > MAX_RETRY) {
            api.warn("ip: identity provider give up after " + MAX_RETRY + " attempts");
            return;
        }
        try {
            try {
                installByteProvider("mq0.a", "e", true);  // metadata (6.3.0)
                installByteProvider("mq0.a", "d", false); // device
            } catch (Throwable oldMissing) {
                // 6.4.0: mq0.a 另作他用；REST 身份 provider 迁到 oq0.C0999a（e/d 同名，
                // 见 Cq0.a.intercept 对 C0999a.e()/d() 的调用）
                installByteProvider("oq0.a", "e", true);  // metadata (6.4.0)
                installByteProvider("oq0.a", "d", false); // device
            }
            identityReady.set(true);
            api.info("ip: identity provider hooks ok (attempt=" + identityAttempts.get() + ")");
        } catch (ClassNotFoundException e) {
            api.debug("ip: identity class not loaded yet, retry in " + RETRY_DELAY_MS + "ms");
            retry(new Runnable() {
                @Override public void run() {
                    installIdentityProvider();
                }
            });
        } catch (Throwable t) {
            api.error("ip: identity provider install failed", t);
        }
    }

    private void retry(Runnable r) {
        try {
            api.postDelayed(r, RETRY_DELAY_MS);
        } catch (Throwable t) {
            api.warn("ip: retry scheduling failed: " + t);
        }
    }

    /** 改写成功日志：verbose 常开；否则每类只打首条（进程生命周期内）。 */
    private void logRewrite(AtomicBoolean once, String msg) {
        if (!api.isVerboseLoggingEnabled() && !once.compareAndSet(false, true)) return;
        api.info("[探针] ip: " + msg);
    }

    private void installByteProvider(final String clsName, final String methodName,
                                     final boolean isMetadata) throws Throwable {
        final Class<?> c = api.load(cl, clsName);
        final Method m = api.declaredMethod(c, methodName);
        api.deoptimize(m);
        api.addHook("ip: identity " + clsName + "." + methodName, m, new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                if (!api.isIpLocationEnabled()) return result;
                if (probeIdpFire.compareAndSet(false, true)) {
                    api.info("ip: identity provider fired " + clsName + "." + methodName
                            + " scope=" + sScope.get());
                }
                if (sScope.get() == null) return result;
                if (!(result instanceof byte[])) return result;
                byte[] rewritten = rewriteIdentity((byte[]) result, isMetadata);
                if (rewritten != null) {
                    if (api.isVerboseLoggingEnabled()) {
                        api.info("ip: rewritten " + clsName + "." + methodName
                                + " (" + ((byte[]) result).length + " -> " + rewritten.length + " bytes)");
                    } else {
                        logRewrite(probeIdp, "identity/" + methodName + " 改写生效");
                    }
                    return rewritten;
                }
                return result;
            }
        });
        api.info("ip: identity provider hook ok -> " + clsName + "." + methodName);
    }

    /** 用 protobuf 解析并改写身份字段（旧 moss / REST）。
     *  6.4.0 proto 类改名：Metadata->KMetadata、Device->KDevice；类加载失败时
     *  兜底用字节级 mobi_app 替换（长度前缀校验，安全幂等）。 */
    private byte[] rewriteIdentity(byte[] src, boolean isMetadata) {
        String[] candidates = isMetadata
                ? new String[]{"com.bapis.bilibili.metadata.Metadata", "com.bapis.bilibili.metadata.KMetadata"}
                : new String[]{"com.bapis.bilibili.metadata.device.Device", "com.bapis.bilibili.metadata.device.KDevice"};
        try {
            for (String cn : candidates) {
                Class<?> cls = loadQuiet(cn);
                if (cls == null) continue;
                Object msg = invokeStatic(cls, "parseFrom", new Class[]{byte[].class}, src);
                if (msg == null) continue;
                Object builder = call(msg, "toBuilder");
                call(builder, "setMobiApp", MOBI_APP);
                call(builder, "setBuild", Integer.valueOf(BUILD));
                call(builder, "setChannel", CHANNEL);
                if (!isMetadata) {
                    call(builder, "setAppId", Integer.valueOf(APP_ID));
                    call(builder, "setVersionName", VERSION_NAME);
                }
                Object built = call(builder, "build");
                Object out = call(built, "toByteArray");
                if (out instanceof byte[]) return (byte[]) out;
            }
        } catch (Throwable t) {
            api.warn("ip: protobuf rewrite: " + t);
        }
        return rewriteMobiAppBytes(src);
    }

    /** 6.4.0 空间页 REST 参数改写：空间身份走 URL 参数（mobi_app=android_i），
     *  不走 moss/proto 头。挂空间页专属拦截器 e.addCommonParam（天然定域：只有空间请求经过它）。 */
    private void installSpaceRestParams() {
        String[] cand = {"com.bilibili.app.comm.list.common.api.e"};
        for (final String cn : cand) {
            try {
                Class<?> c = api.load(cl, cn);
                Method m = null;
                for (Method mm : c.getDeclaredMethods()) {
                    if (!mm.getName().equals("addCommonParam")) continue;
                    Class<?>[] ps = mm.getParameterTypes();
                    if (ps.length == 1 && java.util.Map.class.isAssignableFrom(ps[0])) { m = mm; break; }
                }
                if (m == null) {
                    api.debug("ip: space rest params " + cn + ".addCommonParam not found");
                    continue;
                }
                api.deoptimize(m);
                api.addHook("ip: space rest params", m, new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Object result = chain.proceed();
                        if (!api.isIpLocationEnabled()) return result;
                        try {
                            Object mapObj = chain.getArg(0);
                            if (mapObj instanceof java.util.Map) {
                                java.util.Map<?, ?> map = (java.util.Map<?, ?>) mapObj;
                                Object cur = map.get("mobi_app");
                                if (cur != null && String.valueOf(cur).contains("android_i")) {
                                    @SuppressWarnings("unchecked")
                                    java.util.Map<Object, Object> raw = (java.util.Map<Object, Object>) mapObj;
                                    raw.put("mobi_app", "android");
                                    if (spaceParamFired.compareAndSet(false, true)) {
                                        api.info("ip: space rest params rewritten mobi_app " + cur + " -> android");
                                    }
                                }
                            }
                        } catch (Throwable t0) {
                            api.debug("ip: space rest params rewrite failed: " + t0);
                        }
                        return result;
                    }
                });
                api.info("ip: space rest params hook ok -> " + cn + ".addCommonParam");
            } catch (Throwable t) {
                api.debug("ip: space rest params " + cn + " unavailable: " + t);
            }
        }
    }

    /** 全量 Activity 探针：记录去重类名，定位用户真实打开的页面（cap 40）。 */
    private void installActivityProbe() {
        try {
            final Class<?> act = api.load(cl, "android.app.Activity");
            Method up = act.getDeclaredMethod("onResume");
            api.deoptimize(up);
            api.addHook("ip: activity probe", up, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    try {
                        Object thiz = chain.getThisObject();
                        if (thiz != null) {
                            String n = thiz.getClass().getName();
                            if (seenActivities.size() < 40 && seenActivities.add(n)) {
                                api.info("ip: activity: " + n);
                            }
                        }
                    } catch (Throwable ignore0) { }
                    return chain.proceed();
                }
            });
            api.info("ip: activity probe ok");
        } catch (Throwable t) {
            api.debug("ip: activity probe unavailable: " + t);
        }
    }

    /** 6.4.0 空间页 UI 定域：AuthorSpaceActivity 打开期间放行 kr1.a.a 改写（窗口制）。
     *  6.4.0 空间 REST 由 kntr 直连 provider，svc 无法定位，只能按页面定位。 */
    private void installSpaceUiScope() {
        // 6.4.0 实测用户打开的空间页 = LocalAuthorSpaceActivity；AuthorSpaceActivity 为 6.3.0/遗留候选
        String[] acts = {"com.bilibili.app.authorspace.local.LocalAuthorSpaceActivity",
                "com.bilibili.app.authorspace.ui.AuthorSpaceActivity"};
        for (final String actName : acts) {
            try {
                final Class<?> act = api.load(cl, actName);
                Method up = null, down = null;
                try { up = act.getDeclaredMethod("onResume"); } catch (Throwable ig1) { }
                try { down = act.getDeclaredMethod("onPause"); } catch (Throwable ig2) { }
                hookSpaceActivity(actName, act, up, down);
            } catch (Throwable t) {
                api.debug("ip: space ui scope " + actName + " unavailable: " + t);
            }
        }
    }

    private void hookSpaceActivity(final String actName, Class<?> act, Method up, Method down) {
        try {
            if (up != null) {
                api.deoptimize(up);
                api.addHook("ip: space page open", up, new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        if (sUiSpaceUntil == 0L) {
                            api.info("ip: space page open -> identity armed (15s window)");
                        }
                        sUiSpaceUntil = System.currentTimeMillis() + 15000L;
                        return chain.proceed();
                    }
                });
                api.info("ip: space ui scope hook ok -> " + actName + ".onResume");
            }
            if (down != null) {
                api.deoptimize(down);
                api.addHook("ip: space page close", down, new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        if (sUiSpaceUntil != 0L) {
                            api.info("ip: space page close -> identity disarmed");
                        }
                        sUiSpaceUntil = 0L;
                        return chain.proceed();
                    }
                });
            }
        } catch (Throwable t) {
            api.debug("ip: space ui scope " + actName + " hook failed: " + t);
        }
    }

    /** 空间页传输通道探测：全局 OkHttpClient.newCall 抓 space 相关 URL（一次性/URL）。 */
    private void installNewCallProbe() {
        try {
            final Class<?> client = api.load(cl, "okhttp3.OkHttpClient");
            Method nc = null;
            for (Method mm : client.getDeclaredMethods()) {
                if (!mm.getName().equals("newCall")) continue;
                Class<?>[] ps = mm.getParameterTypes();
                if (ps.length == 1 && ps[0].getName().equals("okhttp3.Request")) { nc = mm; break; }
            }
            if (nc == null) {
                api.debug("ip: OkHttpClient.newCall not found");
                return;
            }
            final Method reqUrl = urlMethodOf(client);
            api.deoptimize(nc);
            api.addHook("ip: newcall probe", nc, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    try {
                        Object req = chain.getArg(0);
                        if (req != null && reqUrl != null) {
                            Object u = reqUrl.invoke(req);
                            String us = u == null ? null : String.valueOf(u);
                            if (us != null) {
                                String low = us.toLowerCase();
                                if (low.contains("space") && urlProbeSeen.add(low.substring(0, Math.min(120, low.length())))) {
                                    api.info("ip: newcall url probe: " + us.substring(0, Math.min(160, us.length())));
                                }
                            }
                        }
                    } catch (Throwable ignore0) { }
                    return chain.proceed();
                }
            });
            api.info("ip: newcall probe ok");
        } catch (Throwable t) {
            api.debug("ip: newcall probe unavailable: " + t);
        }
    }

    private Method urlMethodOf(Class<?> client) {
        try {
            Class<?> req = client.getClassLoader().loadClass("okhttp3.Request");
            Class<?> hu = client.getClassLoader().loadClass("okhttp3.HttpUrl");
            for (Method mm : req.getMethods()) {
                if (mm.getParameterTypes().length == 0 && mm.getReturnType() == hu
                        && (mm.getName().equals("Url") || mm.getName().equals("url"))) {
                    mm.setAccessible(true);
                    return mm;
                }
            }
        } catch (Throwable ignore) { }
        return null;
    }

    /** REST 评论/主页：按 URL 判定并改写 okhttp 请求参数。
     *  6.3.0: Aq0.a.intercept；6.4.0: Aq0.a.intercept 消失，okhttp 拦截器迁到 Cq0.a。 */
    private void installRest() throws Throwable {
        installNewCallProbe();
        installSpaceUiScope();
        installActivityProbe();
        installSpaceRestParams();
        Class<?> aq0a = null;
        String restClsUsed = null;
        for (String cn : new String[]{"Aq0.a", "Cq0.a"}) {
            try {
                Class<?> c = api.load(cl, cn);
                boolean has = false;
                for (Method mm : c.getDeclaredMethods()) {
                    if (mm.getName().equals("intercept") && mm.getParameterTypes().length == 1) { has = true; break; }
                }
                if (has) { aq0a = c; restClsUsed = cn; break; }
            } catch (Throwable next) {
                // 下一候选
            }
        }
        if (aq0a == null) {
            api.warn("ip: rest interceptor (Aq0.a/Cq0.a) not present; skip");
            return;
        }
        final String restCls = restClsUsed;
        Method m = null;
        for (Method mm : aq0a.getDeclaredMethods()) {
            if (mm.getName().equals("intercept") && mm.getParameterTypes().length == 1) {
                m = mm;
                break;
            }
        }
        api.deoptimize(m);
        final Class<?> chainCls = m.getParameterTypes()[0];
        final Method reqMethod = reqMethod(chainCls);
        final Method urlMethod = urlMethod(reqMethod);
        api.addHook("ip: rest scope", m, new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                if (!api.isIpLocationEnabled()) return chain.proceed();
                Object chainObj = chain.getArg(0);
                Object req = reqMethod != null ? api.invoke(reqMethod, chainObj) : null;
                String url = urlString(urlMethod, req);
                if (url != null) {
                    String low = url.toLowerCase();
                    if ((low.contains("space") || low.contains("feed") || low.contains("region"))
                            && urlProbeSeen.add(low.substring(0, Math.min(120, low.length())))) {
                        api.info("ip: rest url probe: " + url.substring(0, Math.min(160, url.length())));
                    }
                }
                if (api.isVerboseLoggingEnabled()) {
                    api.info("ip: rest intercept url=" + url);
                }
                String kind = classifyRest(url);
                if (kind == null) return chain.proceed();
                String old = sScope.get();
                if (old == null) {
                    sScope.set(kind);
                    try {
                        return chain.proceed();
                    } finally {
                        sScope.remove();
                    }
                } else {
                    try {
                        return chain.proceed();
                    } finally {
                        sScope.set(old);
                    }
                }
            }
        });
        api.info("ip: rest interceptor hook ok -> " + restCls + ".intercept");
    }

    /** REST 公共参数注入点：XA0.a 是 okretro 所有参数拦截器的基类。
     *  addCommonParamToUrl(t, z.a) 接收 URL 并在内部调用 addCommonParam(Map)（同线程）。
     *  hook addCommonParamToUrl 记录 URL（ThreadLocal），
     *  hook addCommonParam 按 URL 判定主页/评论并改写 map。
     *  主页 -> android（国内版普通），评论 -> android_hd。 */
    private void installRestParams() {
        if (restParamsAttempts.incrementAndGet() > MAX_RETRY) {
            // 6.4.0 okretro 参数基类移除；REST 公共参数路径已由 Cq0.a 拦截器覆盖
            api.warn("ip: rest params (XA0.a) not present in this version; give up");
            return;
        }
        try {
            final Class<?> xa0 = api.load(cl, "XA0.a");
            // addCommonParamToUrl(t, z.a)：记录 URL
            Method toUrl = null;
            for (Method mm : xa0.getDeclaredMethods()) {
                if (mm.getName().equals("addCommonParamToUrl") && mm.getParameterTypes().length == 2) {
                    toUrl = mm;
                    break;
                }
            }
            if (toUrl != null) {
                api.deoptimize(toUrl);
                api.addHook("ip: rest url scope", toUrl, new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        if (!api.isIpLocationEnabled()) return chain.proceed();
                        Object tVar = chain.getArg(0);
                        String url = tVar == null ? null : tVar.toString();
                        String kind = classifyRest(url);
                        if (kind == null) return chain.proceed();
                        String old = sScope.get();
                        if (old == null) {
                            sScope.set(kind);
                            try {
                                return chain.proceed();
                            } finally {
                                sScope.remove();
                            }
                        } else {
                            try {
                                return chain.proceed();
                            } finally {
                                sScope.set(old);
                            }
                        }
                    }
                });
                api.info("ip: rest url scope hook ok");
            }
            // addCommonParam(Map)：改写 mobi_app/build/channel
            Method acp = null;
            for (Method mm : xa0.getDeclaredMethods()) {
                if (mm.getName().equals("addCommonParam") && mm.getParameterTypes().length == 1
                        && mm.getParameterTypes()[0] == java.util.Map.class) {
                    acp = mm;
                    break;
                }
            }
            if (acp != null) {
                api.deoptimize(acp);
                api.addHook("ip: rest params", acp, new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Object result = chain.proceed();
                        if (!api.isIpLocationEnabled()) return result;
                        String scope = sScope.get();
                        if (scope == null) return result;
                        Object arg = chain.getArg(0);
                        if (arg instanceof java.util.Map) {
                            java.util.Map map = (java.util.Map) arg;
                            if (scope.startsWith("profile")) {
                                map.put("mobi_app", "android");
                                map.put("build", String.valueOf(2001100));
                                map.put("channel", "master");
                                if (api.isVerboseLoggingEnabled()) {
                                    api.info("ip: rest params rewritten: profile -> mobi_app=android");
                                } else {
                                    logRewrite(probeRest, "rest/profile 改写生效");
                                }
                            } else if (scope.startsWith("comment")) {
                                map.put("mobi_app", "android");
                                map.put("build", String.valueOf(BUILD));
                                map.put("channel", CHANNEL);
                                if (api.isVerboseLoggingEnabled()) {
                                    api.info("ip: rest params rewritten: comment -> mobi_app=android");
                                } else {
                                    logRewrite(probeRest, "rest/comment 改写生效");
                                }
                            }
                        }
                        return result;
                    }
                });
                api.info("ip: rest params hook ok -> XA0.a.addCommonParam");
            }
        } catch (ClassNotFoundException e) {
            api.debug("ip: XA0.a not loaded yet, retry");
            retry(new Runnable() {
                @Override public void run() {
                    installRestParams();
                }
            });
        } catch (Throwable t) {
            api.error("ip: rest params hook failed", t);
        }
    }

    /** 字节流内查找子串，返回下标（无则 -1）。 */
    private static int indexOfBytes(byte[] hay, String needle) {
        byte[] n = needle.getBytes();
        outer: for (int i = 0; i <= hay.length - n.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (hay[i + j] != n[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    /** 在 protobuf 字节流中把 android_i 替换为 android_hd。
     *  protobuf string 字段格式：tag(1) + length(varint) + bytes。
     *  android_i(9字节) -> android_hd(10字节)，需重建数组并更新 length 前缀。
     *  返回新数组（未改写则返回 null）。 */
    private static byte[] rewriteMobiAppBytes(byte[] src) {
        if (src == null) return null;
        byte[] oldStr = "android_i".getBytes();
        byte[] newStr = "android".getBytes(); // 统一普通版身份（评论区/主页均生效）
        int foundIdx = -1;
        for (int i = 0; i <= src.length - oldStr.length; i++) {
            boolean match = true;
            for (int j = 0; j < oldStr.length; j++) {
                if (src[i + j] != oldStr[j]) { match = false; break; }
            }
            if (match) { foundIdx = i; break; }
        }
        if (foundIdx < 0) return null;
        int lenPos = foundIdx - 1;
        if (lenPos < 0) return null;
        int oldLen = src[lenPos] & 0xFF;
        if (oldLen != oldStr.length) {
            return null;
        }
        int newLen = newStr.length;
        byte[] out = new byte[src.length + (newLen - oldLen)];
        System.arraycopy(src, 0, out, 0, lenPos);
        out[lenPos] = (byte) newLen;
        System.arraycopy(src, lenPos + 1, out, lenPos + 1, foundIdx - (lenPos + 1));
        System.arraycopy(newStr, 0, out, foundIdx, newLen);
        System.arraycopy(src, foundIdx + oldLen, out, foundIdx + newLen, src.length - (foundIdx + oldLen));
        return out;
    }

    private static String classifyRest(String url) {
        if (url == null) return null;
        try {
            String lower = url.toLowerCase();
            if (lower.contains("/x/v2/space")) return "profile-rest";
            for (String p : new String[]{
                    "/x/v2/reply", "/x/v2/reply/main", "/x/v2/reply/reply",
                    "/x/v2/reply/reply/cursor", "/x/v2/reply/folded",
                    "/x/v2/reply/reply/folded", "/x/v2/reply/msg_feed_list"}) {
                if (lower.contains(p)) return "comment-rest";
            }
        } catch (Throwable t) { /* ignore */ }
        return null;
    }

    // ---- 反射辅助 ----

    private Class<?> loadQuiet(String name) {
        try {
            return api.load(cl, name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String strField(Object obj, String name) {
        try {
            Object v = getField(obj, name);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object getField(Object obj, String name) {
        try {
            Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isReplyMethod(String method) {
        if (method == null) return false;
        for (String s : REPLY_METHODS) {
            if (s.equals(method)) return true;
        }
        return false;
    }

    private static Object invokeStatic(Class<?> cls, String name, Class<?>[] sig, Object... args) {
        try {
            Method m = cls.getMethod(name, sig);
            m.setAccessible(true);
            return m.invoke(null, args);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object call(Object obj, String name, Object... args) {
        if (obj == null) return null;
        try {
            Method m = null;
            for (Method mm : obj.getClass().getMethods()) {
                if (mm.getName().equals(name) && mm.getParameterTypes().length == args.length) {
                    m = mm;
                    break;
                }
            }
            if (m == null) return null;
            m.setAccessible(true);
            return m.invoke(obj, args);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method reqMethod(Class<?> chainCls) {
        try {
            for (Method m : chainCls.getMethods()) {
                if (m.getName().equals("request") && m.getParameterTypes().length == 0) {
                    return m;
                }
            }
        } catch (Throwable t) { /* ignore */ }
        return null;
    }

    private static Method urlMethod(Method reqMethod) {
        if (reqMethod == null) return null;
        try {
            Class<?> reqCls = reqMethod.getReturnType();
            for (Method m : reqCls.getMethods()) {
                if (m.getName().equals("l") && m.getParameterTypes().length == 0) {
                    return m; // okhttp3 Request.l() -> HttpUrl
                }
            }
            for (Method m : reqCls.getMethods()) {
                if (m.getName().equals("url") && m.getParameterTypes().length == 0) {
                    return m;
                }
            }
        } catch (Throwable t) { /* ignore */ }
        return null;
    }

    private static String urlString(Method urlMethod, Object req) {
        if (urlMethod == null || req == null) return null;
        try {
            Object u = urlMethod.invoke(req);
            if (u == null) return null;
            return u.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}