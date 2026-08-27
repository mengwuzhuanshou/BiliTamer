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
    private static final String DM_SERVICE = "bilibili.community.service.dm.v1";
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

    // 运行时探针：verbose 关闭时，每类改写的第一条必打一行（无 logcat 也能确认活体）
    private final AtomicBoolean probeKmp = new AtomicBoolean(false);
    private final AtomicBoolean probeCommon = new AtomicBoolean(false);
    private final AtomicBoolean probeIdp = new AtomicBoolean(false);
    private final AtomicBoolean probeRest = new AtomicBoolean(false);

    private static final ThreadLocal<String> sScope = new ThreadLocal<String>();
    /** 评论区限定模式：moss-common-headers 拦截器在 proceed 前设置的本次 RPC 服务名。
     *  up1.a.a() 在该拦截器内部被同步调用（同线程），凭此标记精确改写。 */
    private static final ThreadLocal<String> sCommonScope = new ThreadLocal<String>();

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
        try {
            final Class<?> cls = api.load(cl, "up1.a");
            final Method m = api.declaredMethod(cls, "a");
            api.deoptimize(m);
            api.addHook("ip: kmp header value", m, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    if (!api.isIpLocationEnabled() && !api.isAiSubtitleEnabled()) return result;
                    if (api.getIpScopeMode() == BiliConfig.IP_SCOPE_COMMENT) {
                        // 评论区限定模式：仅当本次 RPC 是评论/字幕服务时改写
                        if (sCommonScope.get() == null) return result;
                    }
                    if (result == null) return result;
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
            api.info("ip: kmp header value hook ok (attempt=" + kmpHeaderAttempts.get() + ")");
        } catch (ClassNotFoundException e) {
            api.debug("ip: up1.a not loaded yet, retry in " + RETRY_DELAY_MS + "ms");
            retry(new Runnable() {
                @Override public void run() {
                    installKmpHeaderValue();
                }
            });
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
            boolean aiSub = api.isAiSubtitleEnabled();
            if (!ip && !aiSub) return null;
            // chain -> grpc.c 上下文（MossInterceptor.b.a()）
            Object ctx = callNoArg(chainObj, "a", "MossInterceptor$e");
            if (ctx == null) ctx = callNoArg(chainObj, "a", "ignet.impl.grpc.c");
            if (ctx == null) ctx = fieldInHierarchy(chainObj, "a");
            if (ctx == null) {
                logRewriteOnce("ctx", "ip: common headers ctx not found (chain=" + chainObj.getClass().getName() + ")");
                return null;
            }
            Object g = fieldTypedInHierarchy(ctx, "b", "jp1.g");
            String svc = g == null ? null : strField(g, "a");
            String method = g == null ? null : strField(g, "c");
            if (svc == null) {
                // 兜底：k 也有服务名字段
                Object k = fieldTypedInHierarchy(ctx, "a", "jp1.k");
                svc = k == null ? null : strField(k, "a");
            }
            rememberService(svc);
            boolean want = (ip && isReplyService(svc)) || (aiSub && isDmService(svc));
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

    private void rememberService(String svc) {
        if (svc == null || svc.length() == 0) return;
        if (seenServices.size() >= 40) return;
        if (seenServices.add(svc)) {
            api.debug("ip: common headers interceptor saw service=" + svc);
        }
    }

    /** 评论区服务判定（按服务名，覆盖该服务全部方法）。 */
    private static boolean isReplyService(String svc) {
        if (svc == null) return false;
        return svc.equals(REPLY_SERVICE) || svc.startsWith("bilibili.main.community.reply");
    }

    /** 弹幕/AI 字幕服务判定。 */
    private static boolean isDmService(String svc) {
        if (svc == null) return false;
        return svc.equals(DM_SERVICE) || svc.startsWith("bilibili.community.service.dm");
    }

    /** 改写 Metadata/Device 身份头（旧 moss / REST 路径）。 */
    private void installIdentityProvider() {
        if (identityReady.get()) return;
        if (identityAttempts.incrementAndGet() > MAX_RETRY) {
            api.warn("ip: identity provider give up after " + MAX_RETRY + " attempts");
            return;
        }
        try {
            installByteProvider("mq0.a", "e", true);  // metadata
            installByteProvider("mq0.a", "d", false); // device
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

    /** 用 protobuf 解析并改写身份字段（旧 moss / REST）。 */
    private byte[] rewriteIdentity(byte[] src, boolean isMetadata) {
        try {
            if (isMetadata) {
                Class<?> cls = loadQuiet("com.bapis.bilibili.metadata.Metadata");
                if (cls == null) return null;
                Object msg = invokeStatic(cls, "parseFrom", new Class[]{byte[].class}, src);
                Object builder = call(msg, "toBuilder");
                call(builder, "setMobiApp", MOBI_APP);
                call(builder, "setBuild", Integer.valueOf(BUILD));
                call(builder, "setChannel", CHANNEL);
                Object built = call(builder, "build");
                Object out = call(built, "toByteArray");
                return out instanceof byte[] ? (byte[]) out : null;
            } else {
                Class<?> cls = loadQuiet("com.bapis.bilibili.metadata.device.Device");
                if (cls == null) return null;
                Object msg = invokeStatic(cls, "parseFrom", new Class[]{byte[].class}, src);
                Object builder = call(msg, "toBuilder");
                call(builder, "setMobiApp", MOBI_APP);
                call(builder, "setBuild", Integer.valueOf(BUILD));
                call(builder, "setChannel", CHANNEL);
                call(builder, "setAppId", Integer.valueOf(APP_ID));
                call(builder, "setVersionName", VERSION_NAME);
                Object built = call(builder, "build");
                Object out = call(built, "toByteArray");
                return out instanceof byte[] ? (byte[]) out : null;
            }
        } catch (Throwable t) {
            api.warn("ip: protobuf rewrite: " + t);
            return null;
        }
    }

    /** REST 评论/主页：按 URL 判定并改写 okhttp 请求参数。 */
    private void installRest() throws Throwable {
        final Class<?> aq0a = api.load(cl, "Aq0.a");
        Method m = null;
        for (Method mm : aq0a.getDeclaredMethods()) {
            if (mm.getName().equals("intercept") && mm.getParameterTypes().length == 1) {
                m = mm;
                break;
            }
        }
        if (m == null) {
            api.warn("ip: Aq0.a.intercept not found");
            return;
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
                if (api.isVerboseLoggingEnabled()) {
                    api.info("ip: Aq0.a intercept url=" + url);
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
        api.info("ip: rest interceptor hook ok -> Aq0.a.intercept");
    }

    /** REST 公共参数注入点：XA0.a 是 okretro 所有参数拦截器的基类。
     *  addCommonParamToUrl(t, z.a) 接收 URL 并在内部调用 addCommonParam(Map)（同线程）。
     *  hook addCommonParamToUrl 记录 URL（ThreadLocal），
     *  hook addCommonParam 按 URL 判定主页/评论并改写 map。
     *  主页 -> android（国内版普通），评论 -> android_hd。 */
    private void installRestParams() {
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