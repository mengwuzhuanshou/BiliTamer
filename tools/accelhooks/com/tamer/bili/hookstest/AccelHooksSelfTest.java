package com.tamer.bili.hookstest;

import com.bilibili.ijk.media.AndroidNativeMediaAsset;
import com.bilibili.ijk.media.DrmInfo;
import com.bilibili.ijk.media.IJKDashStream;
import com.bilibili.ijk.media.MediaAsset;
import com.bilibili.ijk.media.MediaAssetScheme;
import com.bilibili.ijk.media.MultiFlvSegment;
import com.tamer.bili.accel.AccelEngine;
import com.tamer.bili.accel.AccelProxy;
import com.tamer.bili.accel.Transport;
import com.tamer.bili.hooks.AccelHooks;
import com.tamer.bili.hooks.HookApi;

import io.github.libxposed.api.XposedInterface;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * AccelHooks 桌面端到端自测：用与宿主同形状的假类 + 假 HookApi/Chain 驱动真实的
 * hook 逻辑，CDN 侧注入确定性假 Transport（不出网）。覆盖：
 * 默认关零安装；Normal/Dash/MultiFlv 改写与字段保真；DRM/白名单外不接管；
 * 改写地址经真实回环代理取回完整字节（并落块缓存）；Range 语义；
 * 旧管线 bundle 出参改写；SingleSpec ctor 改写 + DB 还原重挂（防代理套代理）；
 * AIDL 控制动词与 spec 键一致；事件回传 unwrap 回原始地址；形状筛子不误伤。
 */
public final class AccelHooksSelfTest {

    private static final String PKG = "hookstest";
    private static int checks = 0;
    private static int failures = 0;
    private static int mainPort = -1;

    private static final String MEDIA_URL =
            "https://upz.m.bilivideo.com/vid/1.m4s?deadline=1700000000&sign=abc123&trid=x";
    private static final String MEDIA_BACKUP =
            "https://backup1.bilivideo.com/vid/b1.m4s?sign=2";
    private static final String MEDIA_URL2 =
            "https://xy.m.bilivideo.com/vid/2.m4s?deadline=1&sign=zz";
    private static final String NOT_MEDIA = "https://img.example.com/a.mp4";
    private static final String FREEFLOW = "http://fc.linkmobile.com.cn/f.flv";

    private static final int TOTAL = 100 * 1024;
    private static final byte[] CONTENT = new byte[TOTAL];

    static {
        for (int i = 0; i < TOTAL; i++) {
            CONTENT[i] = (byte) (i * 31 + 7);
        }
    }

    private AccelHooksSelfTest() {
    }

    public static void main(String[] args) throws Throwable {
        testDefaultOff();
        testMainProcess();
        testDownloadProcess();
        cleanupCache();
        System.out.println("checks=" + checks + " failures=" + failures);
        System.out.println(failures == 0 ? "ALL PASS" : "FAILURES PRESENT");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ===== 场景 1：默认关 = 一个 hook 不装、一个端口不开 =====

    private static void testDefaultOff() throws Exception {
        System.out.println("== accel-hooks: default off ==");
        FakeApi api = new FakeApi();
        api.accelEnabled = false;
        AccelHooks hooks = new AccelHooks(api, null, false, PKG, new FakeCdn());
        hooks.install();
        check(api.hooks.isEmpty(), "disabled installs zero hooks");
        check(hooks.port() == -1, "disabled starts no proxy");
        check(api.hasLog("disabled"), "disabled leaves an explanatory log");
        hooks.close();
    }

    // ===== 场景 2：主进程（播放侧） =====

    private static void testMainProcess() throws Throwable {
        System.out.println("== accel-hooks: main process ==");
        FakeApi api = new FakeApi();
        api.accelEnabled = true;
        AccelHooks hooks = new AccelHooks(api, null, false, PKG, new FakeCdn());
        hooks.install();
        int port = hooks.port();
        mainPort = port;
        check(port > 0, "proxy listening");
        check(api.find("nativeAsset") != null, "nativeAsset hook installed");
        check(api.find("addDashStream") != null, "addDashStream hook installed");
        check(api.find("legacy onNativeInvoke") != null, "legacy binder hook installed");
        check(api.find("SingleSpec") == null, "offline spec hook NOT in main");
        check(api.find("remote verb") == null, "offline verbs NOT in main");

        // ---- Normal 接管 + 字段保真 ----
        Method create = com.bilibili.ijk.media.AndroidNativeMediaAssetKt.class
                .getDeclaredMethod("createNativeAsset", MediaAsset.class);
        List<String> backups = Arrays.asList(MEDIA_BACKUP);
        HashMap<String, String> option = new HashMap<String, String>();
        option.put("hdr", "1");
        MediaAsset.Normal normal = new MediaAsset.Normal(MEDIA_URL, backups,
                MediaAssetScheme.VOD, option, null);
        AndroidNativeMediaAsset out = (AndroidNativeMediaAsset) api.fire(create, null, normal);
        MediaAsset.Normal got = (MediaAsset.Normal) out.asset;
        check(got != normal, "Normal rebuilt");
        check(proxyOf(port, MEDIA_URL).equals(got.getUrl()), "Normal url proxied");
        check(backups.equals(got.getBackupUrlList()), "Normal backups preserved");
        check(got.getScheme() == MediaAssetScheme.VOD, "Normal scheme preserved");
        check(option.equals(got.getOption()), "Normal option preserved");
        check(got.getDrm() == null, "Normal drm stays null");

        // ---- 幂等：已是代理形态不套娃、按当前端口重挂 ----
        MediaAsset.Normal rewrapped = new MediaAsset.Normal(proxyOf(port, MEDIA_URL), backups,
                MediaAssetScheme.VOD, option, null);
        AndroidNativeMediaAsset out2 = (AndroidNativeMediaAsset) api.fire(create, null, rewrapped);
        MediaAsset.Normal got2 = (MediaAsset.Normal) out2.asset;
        check(proxyOf(port, MEDIA_URL).equals(got2.getUrl()), "re-anchor keeps current port");
        check(!doubleWrapped(got2.getUrl()), "no proxy-of-proxy");

        // ---- 改写后的地址真能经代理取回完整字节（假 CDN 走注入 transport）----
        byte[] served = httpGet(proxyOf(port, MEDIA_URL), -1, -1);
        check(served.length == TOTAL, "proxy serves full " + TOTAL + " bytes, got " + served.length);
        boolean same = served.length == TOTAL;
        for (int i = 0; same && i < served.length; i += 397) {
            same = served[i] == CONTENT[i];
        }
        check(same, "served bytes match fake CDN content");
        check(cacheHasFiles(), "block cache files written under host files dir");

        // ---- Range 语义：中段区间 ----
        byte[] slice = httpGet(proxyOf(port, MEDIA_URL), 1000, 1999);
        check(slice.length == 1000 && slice[0] == CONTENT[1000] && slice[999] == CONTENT[1999],
                "proxy honors mid Range 1000-1999");

        // ---- Dash：两视频一音频全改写，元数据保真 ----
        IJKDashStream v1 = new IJKDashStream(16, 13, 80, 5000000, MEDIA_URL, backups);
        IJKDashStream v2 = new IJKDashStream(16, 12, 64, 3000000, MEDIA_URL2, null);
        IJKDashStream a1 = new IJKDashStream(1, 0, 0, 200000, MEDIA_BACKUP, null);
        List<IJKDashStream> video = new ArrayList<IJKDashStream>(Arrays.asList(v1, v2));
        List<IJKDashStream> audio = new ArrayList<IJKDashStream>(Arrays.asList(a1));
        MediaAsset.Dash dash = new MediaAsset.Dash(0, 64, video, audio,
                MediaAssetScheme.VOD, option, null);
        MediaAsset.Dash gotDash = (MediaAsset.Dash)
                ((AndroidNativeMediaAsset) api.fire(create, null, dash)).asset;
        check(gotDash != dash, "Dash rebuilt");
        check(proxyOf(port, MEDIA_URL).equals(gotDash.getVideoStreamList().get(0).getUrl()),
                "Dash video[0] proxied");
        check(proxyOf(port, MEDIA_URL2).equals(gotDash.getVideoStreamList().get(1).getUrl()),
                "Dash video[1] proxied");
        check(proxyOf(port, MEDIA_BACKUP).equals(gotDash.getAudioStreamList().get(0).getUrl()),
                "Dash audio[0] proxied");
        IJKDashStream s0 = gotDash.getVideoStreamList().get(0);
        check(s0.getMediaType() == 16 && s0.getCodecId() == 13 && s0.getQn() == 80
                        && s0.getBandWidth() == 5000000, "Dash stream metadata preserved");
        check(backups.equals(s0.getBackupUrlList()), "Dash stream backups preserved");
        check(gotDash.getCurVideoQn() == 64 && gotDash.getCurAudioQn() == 0, "Dash qn preserved");
        check(gotDash.getScheme() == MediaAssetScheme.VOD, "Dash scheme preserved");

        // ---- DRM 不接管 ----
        MediaAsset.Normal drm = new MediaAsset.Normal(MEDIA_URL, backups,
                MediaAssetScheme.VOD, option, new DrmInfo("kid-1"));
        AndroidNativeMediaAsset outDrm = (AndroidNativeMediaAsset) api.fire(create, null, drm);
        check(outDrm.asset == drm, "DRM asset untouched");

        // ---- 白名单外不接管 ----
        MediaAsset.Normal offlist = new MediaAsset.Normal(NOT_MEDIA, null,
                MediaAssetScheme.NORMAL, option, null);
        check(((AndroidNativeMediaAsset) api.fire(create, null, offlist)).asset == offlist,
                "non-whitelist host untouched");

        // ---- MultiFlv：混合段（接管段换、无关段原样） ----
        MultiFlvSegment seg1 = new MultiFlvSegment(
                "https://upz.m.bilivideo.com/flv/1-1.flv?sign=a", null, 60000L, 9000000L);
        MultiFlvSegment seg2 = new MultiFlvSegment(NOT_MEDIA, null, 60000L, 1000L);
        List<MultiFlvSegment> segs = new ArrayList<MultiFlvSegment>(Arrays.asList(seg1, seg2));
        MediaAsset.MultiFlv flv = new MediaAsset.MultiFlv(segs, MediaAssetScheme.VOD, option);
        MediaAsset.MultiFlv gotFlv = (MediaAsset.MultiFlv)
                ((AndroidNativeMediaAsset) api.fire(create, null, flv)).asset;
        check(gotFlv != flv, "MultiFlv rebuilt");
        check(proxyOf(port, seg1.getUrl()).equals(gotFlv.getSegmentList().get(0).getUrl()),
                "MultiFlv segment[0] proxied");
        check(NOT_MEDIA.equals(gotFlv.getSegmentList().get(1).getUrl()),
                "MultiFlv segment[1] untouched");
        MultiFlvSegment g0 = gotFlv.getSegmentList().get(0);
        check(g0.getDurationMs() == 60000L && g0.getSize() == 9000000L, "segment meta preserved");

        // ---- addDashStream 虚拟路径 ----
        com.bilibili.ijk.player.AndroidNativePlayerItem item =
                new com.bilibili.ijk.player.AndroidNativePlayerItem();
        Method add = item.getClass().getDeclaredMethod("addDashStream", List.class, List.class);
        List<IJKDashStream> vv = new ArrayList<IJKDashStream>(Arrays.asList(
                new IJKDashStream(16, 13, 80, 1, MEDIA_URL2, null)));
        api.fire(add, item, vv, new ArrayList<IJKDashStream>());
        check(item.calls == 1, "addDashStream proceeded");
        IJKDashStream streamed = (IJKDashStream) item.videoSeen.get(0);
        check(proxyOf(port, MEDIA_URL2).equals(streamed.getUrl()),
                "addDashStream rewrites before native");

        // ---- 旧管线 binder：bundle 出参改写 ----
        tv.danmaku.ijk.media.player.IjkMediaPlayer.IjkMediaPlayerBinder binder =
                new tv.danmaku.ijk.media.player.IjkMediaPlayer.IjkMediaPlayerBinder();
        Method invoke = binder.getClass().getDeclaredMethod("onNativeInvoke",
                int.class, android.os.Bundle.class);
        android.os.Bundle bundle = new android.os.Bundle();
        binder.urlForCase = MEDIA_URL;
        api.fire(invoke, binder, Integer.valueOf(131079), bundle);
        check(proxyOf(port, MEDIA_URL).equals(bundle.getString("url")),
                "legacy case 131079 bundle url proxied");
        android.os.Bundle bundle2 = new android.os.Bundle();
        binder.urlForCase = FREEFLOW;
        api.fire(invoke, binder, Integer.valueOf(131075), bundle2);
        check(FREEFLOW.equals(bundle2.getString("url")), "legacy freeflow carrier untouched");
        android.os.Bundle bundle3 = new android.os.Bundle();
        api.fire(invoke, binder, Integer.valueOf(27), bundle3);
        check(bundle3.getString("url") == null, "legacy async case not touched");

        hooks.close();
    }

    // ===== 场景 3：:download 进程（离线侧） =====

    private static void testDownloadProcess() throws Throwable {
        System.out.println("== accel-hooks: download process ==");
        FakeApi api = new FakeApi();
        api.accelEnabled = true;
        AccelHooks hooks = new AccelHooks(api, null, true, PKG, new FakeCdn());
        hooks.install();
        int port = hooks.port();
        check(port > 0, "download proxy listening");
        check(port != mainPort, "download port differs from main port");
        check(api.find("SingleSpec ctor") != null, "SingleSpec ctor hook installed");
        check(api.find("remote verb cancel") != null, "cancel verb hooked");
        check(api.find("remote verb pause") != null, "pause verb hooked");
        check(api.find("remote verb queryProgress") != null, "queryProgress verb hooked");

        // ---- spec 构造：原始地址 → 代理形态 ----
        Constructor<?> specCtor = com.bilibili.lib.okdownloader.internal.spec.SingleSpec.class
                .getConstructor(String.class, String.class, String.class, String.class,
                        int.class, String.class, int.class, int.class, int.class,
                        long.class, long.class, int.class, int.class, boolean.class,
                        String.class, boolean.class, int.class, int.class,
                        java.util.Map.class, String.class);
        Object[] specArgs = new Object[20];
        specArgs[0] = MEDIA_URL;
        // 基元参数不能传 null（反射拆箱 NPE）：4/6/7/8/11/12/16/17=int 9/10=long 13/15=boolean
        Integer z = Integer.valueOf(0);
        specArgs[4] = z;
        specArgs[6] = z;
        specArgs[7] = z;
        specArgs[8] = z;
        specArgs[9] = Long.valueOf(0L);
        specArgs[10] = Long.valueOf(0L);
        specArgs[11] = z;
        specArgs[12] = z;
        specArgs[13] = Boolean.FALSE;
        specArgs[15] = Boolean.FALSE;
        specArgs[16] = z;
        specArgs[17] = z;
        specArgs[19] = "tail";
        com.bilibili.lib.okdownloader.internal.spec.SingleSpec spec =
                (com.bilibili.lib.okdownloader.internal.spec.SingleSpec)
                        api.fire(specCtor, null, specArgs);
        check(spec != null && proxyOf(port, MEDIA_URL).equals(spec.url),
                "SingleSpec ctor arg0 proxied");

        // ---- DB 还原：旧端口代理形态 → 重挂当前端口，不套娃 ----
        Object[] stale = specArgs.clone();
        stale[0] = proxyOf(41111, MEDIA_URL);
        com.bilibili.lib.okdownloader.internal.spec.SingleSpec spec2 =
                (com.bilibili.lib.okdownloader.internal.spec.SingleSpec)
                        api.fire(specCtor, null, stale);
        check(proxyOf(port, MEDIA_URL).equals(spec2.url), "restored spec re-anchored to live port");
        check(!doubleWrapped(spec2.url), "restored spec not double-wrapped");

        // ---- 非媒体地址 spec 原样 ----
        Object[] nonmedia = specArgs.clone();
        nonmedia[0] = NOT_MEDIA;
        com.bilibili.lib.okdownloader.internal.spec.SingleSpec spec3 =
                (com.bilibili.lib.okdownloader.internal.spec.SingleSpec)
                        api.fire(specCtor, null, nonmedia);
        check(NOT_MEDIA.equals(spec3.url), "non-media spec untouched");

        // ---- 控制动词与 spec 键一致 ----
        com.bilibili.lib.okdownloader.internal.process.ProcessService.a stub =
                new com.bilibili.lib.okdownloader.internal.process.ProcessService.a();
        Method cancel = stub.getClass().getDeclaredMethod("cancel", String.class);
        Object res = api.fire(cancel, stub, MEDIA_URL);
        check(Boolean.TRUE.equals(res), "cancel result passthrough");
        check(proxyOf(port, MEDIA_URL).equals(stub.verbs.get(0)[1]),
                "cancel verb key == spec key");
        Method qp = stub.getClass().getDeclaredMethod("queryProgress", String.class);
        api.fire(qp, stub, MEDIA_URL);
        check(proxyOf(port, MEDIA_URL).equals(stub.verbs.get(1)[1]),
                "queryProgress verb key matches");

        // ---- 事件回传 unwrap ----
        com.bilibili.lib.okdownloader.internal.process.ProcessService svc =
                new com.bilibili.lib.okdownloader.internal.process.ProcessService();
        String[] evNames = {"b", "c", "n", "u"};
        int unwrapped = 0;
        for (int i = 0; i < evNames.length; i++) {
            check(api.find("event unwrap " + evNames[i]) != null, "unwrap hook " + evNames[i]);
            Method m = svc.getClass().getDeclaredMethod(evNames[i], String.class);
            api.fire(m, svc, proxyOf(port, MEDIA_URL));
            Object[] rec = svc.dispatched.get(svc.dispatched.size() - 1);
            if (MEDIA_URL.equals(rec[1])) {
                unwrapped++;
            }
        }
        check(unwrapped == 4, "b/c/n/u events unwrap back to original url");
        Method hm = svc.getClass().getDeclaredMethod("h", int.class, String.class);
        api.fire(hm, svc, Integer.valueOf(7), proxyOf(port, MEDIA_URL));
        check(MEDIA_URL.equals(svc.dispatched.get(svc.dispatched.size() - 1)[1]),
                "h(int,String) unwraps url slot");
        Method em = svc.getClass().getDeclaredMethod("e",
                String.class, String.class, String.class);
        api.fire(em, svc, proxyOf(port, MEDIA_URL), "meta", "other");
        Object[] eRec = svc.dispatched.get(svc.dispatched.size() - 1);
        check(MEDIA_URL.equals(eRec[1]) && "meta".equals(eRec[2]), "e() unwraps url slot only");
        Method sm = svc.getClass().getDeclaredMethod("s",
                long.class, long.class, String.class, List.class);
        api.fire(sm, svc, Long.valueOf(1L), Long.valueOf(2L), proxyOf(port, MEDIA_URL), null);
        check(MEDIA_URL.equals(svc.dispatched.get(svc.dispatched.size() - 1)[1]),
                "s(long,long,String,List) unwraps url slot");
        // 形状筛子不误伤：无 String 参数 / 含非法参数类型 / 非 void 的方法不应被 hook
        check(api.find("event unwrap onCreate") == null, "onCreate not hooked");
        check(api.find("event unwrap attachBaseContext") == null, "attachBaseContext not hooked");
        check(api.find("event unwrap onBind") == null, "onBind (non-void) not hooked");
        Method cm = svc.getClass().getDeclaredMethod("c", String.class);
        api.fire(cm, svc, "plain-non-url");
        check("plain-non-url".equals(svc.dispatched.get(svc.dispatched.size() - 1)[1]),
                "non-proxy strings pass through unwrap untouched");

        hooks.close();
    }

    // ===== 工具 =====

    private static String proxyOf(int port, String original) {
        return AccelEngine.toProxyUrl(port, original, AccelProxy.PATH);
    }

    /** 套娃检测：外层明文 + 内层编码后仍含 127.0.0.1（'.' 不参与转义）＝两次出现。 */
    private static boolean doubleWrapped(String url) {
        int first = url.indexOf("127.0.0.1");
        return first >= 0 && url.indexOf("127.0.0.1", first + 1) >= 0;
    }

    private static byte[] httpGet(String url, long from, long to) throws Exception {
        URLConnection conn = new URL(url).openConnection();
        if (from >= 0) {
            conn.setRequestProperty("Range", "bytes=" + from + "-" + to);
        }
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        java.io.InputStream in = conn.getInputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            sink.write(buf, 0, n);
        }
        in.close();
        return sink.toByteArray();
    }

    private static boolean cacheHasFiles() {
        File dir = new File("/data/data/" + PKG + "/files/bili_tamer_accel");
        String[] names = dir.list();
        if (names == null) {
            return false;
        }
        for (int i = 0; i < names.length; i++) {
            if (names[i].endsWith(".bin") || names[i].endsWith(".idx")) {
                return true;
            }
        }
        return false;
    }

    private static void cleanupCache() {
        deleteTree(new File("/data/data/" + PKG));
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] kids = file.listFiles();
        if (kids != null) {
            for (int i = 0; i < kids.length; i++) {
                deleteTree(kids[i]);
            }
        }
        file.delete();
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("  FAIL: " + what);
        } else {
            System.out.println("  ok: " + what);
        }
    }

    // ===== 假传输：任何 URL 都按确定性内容响应区间 =====

    private static final class FakeCdn implements Transport {
        @Override
        public Response open(String url, long start, long end,
                             java.util.Map<String, String> headers,
                             int connectTimeoutMs, int readTimeoutMs) {
            long total = CONTENT.length;
            if (start < 0 || start >= total) {
                return new Response(416, -1, -1, total, new ByteArrayInputStream(new byte[0]), null);
            }
            long last = end < 0 ? total - 1 : Math.min(end, total - 1);
            byte[] slice = Arrays.copyOfRange(CONTENT, (int) start, (int) last + 1);
            return new Response(206, start, last, total, new ByteArrayInputStream(slice), null);
        }
    }

    // ===== 假 HookApi：记录注册、按注册回放 hooker =====

    private static final class Reg {
        final String name;
        final Executable ex;
        final XposedInterface.Hooker hooker;

        Reg(String name, Executable ex, XposedInterface.Hooker hooker) {
            this.name = name;
            this.ex = ex;
            this.hooker = hooker;
        }
    }

    private static final class FakeApi implements HookApi {
        boolean accelEnabled;
        final List<Reg> hooks = new ArrayList<Reg>();
        final List<String> logs = new ArrayList<String>();

        boolean hasLog(String part) {
            for (int i = 0; i < logs.size(); i++) {
                if (logs.get(i).contains(part)) {
                    return true;
                }
            }
            return false;
        }

        Reg find(String part) {
            for (int i = 0; i < hooks.size(); i++) {
                if (hooks.get(i).name.contains(part)) {
                    return hooks.get(i);
                }
            }
            return null;
        }

        Object fire(Executable ex, Object recv, Object... args) throws Throwable {
            Reg reg = null;
            for (int i = 0; i < hooks.size(); i++) {
                if (sameExe(hooks.get(i).ex, ex)) {
                    reg = hooks.get(i);
                    break;
                }
            }
            TChain chain = new TChain(ex, recv, args);
            Object r = reg == null ? chain.proceed() : reg.hooker.intercept(chain);
            // 构造器语义：hooker 返回值被忽略，实例来自 proceed()（真框架即如此）。
            if (ex instanceof Constructor && chain.produced != null) {
                r = chain.produced;
            }
            return r;
        }

        private static boolean sameExe(Executable a, Executable b) {
            if (a instanceof Method && b instanceof Method) {
                Method ma = (Method) a;
                Method mb = (Method) b;
                return ma.getName().equals(mb.getName())
                        && Arrays.equals(ma.getParameterTypes(), mb.getParameterTypes())
                        && ma.getDeclaringClass() == mb.getDeclaringClass();
            }
            if (a instanceof Constructor && b instanceof Constructor) {
                Constructor<?> ca = (Constructor<?>) a;
                Constructor<?> cb = (Constructor<?>) b;
                return ca.getDeclaringClass() == cb.getDeclaringClass()
                        && Arrays.equals(ca.getParameterTypes(), cb.getParameterTypes());
            }
            return false;
        }

        // --- HookApi 面（未用项全部惰性实现） ---
        @Override
        public void addHook(String name, Method method, XposedInterface.Hooker hooker) {
            hooks.add(new Reg(name, method, hooker));
        }

        @Override
        public void addHookCtor(String name, Constructor<?> ctor, XposedInterface.Hooker hooker) {
            hooks.add(new Reg(name, ctor, hooker));
        }

        @Override
        public void addHookSimple(String name, Class<?> target, String methodName,
                                  CallObserver observer) {
            // 未用
        }

        @Override public void debug(String msg) { logs.add("D " + msg); }
        @Override public void info(String msg) { logs.add("I " + msg); }
        @Override public void warn(String msg) { logs.add("W " + msg); }
        @Override public void error(String msg, Throwable t) { logs.add("E " + msg + " " + t); }

        @Override
        public Field declaredField(Class<?> clazz, String name) throws NoSuchFieldException {
            Field f = clazz.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        }

        @Override
        public Method declaredMethod(Class<?> clazz, String name, Class<?>... p)
                throws NoSuchMethodException {
            Method m = clazz.getDeclaredMethod(name, p);
            m.setAccessible(true);
            return m;
        }

        @Override
        public Method publicMethod(Class<?> clazz, String name, Class<?>... p)
                throws NoSuchMethodException {
            Method m = clazz.getMethod(name, p);
            m.setAccessible(true);
            return m;
        }

        @Override public boolean deoptimize(Method method) {
            return true;
        }

        @Override
        public Object invoke(Method method, Object recv, Object... args) throws Throwable {
            try {
                return method.invoke(recv, args);
            } catch (InvocationTargetException e) {
                throw e.getCause() == null ? e : e.getCause();
            }
        }

        @Override public Class<?> load(ClassLoader cl, String name) throws ClassNotFoundException {
            return Class.forName(name);
        }

        @Override public void postDelayed(Runnable r, long delayMillis) {
            r.run();
        }

        @Override public boolean isMasterEnabled() { return true; }
        @Override public boolean isIpLocationEnabled() { return false; }
        @Override public int getIpScopeMode() { return 1; }
        @Override public int getCodecMode() { return 0; }
        @Override public boolean isCodecHwFilterEnabled() { return true; }
        @Override public int getAudioQuality() { return 0; }
        @Override public int getHdrMode() { return 0; }
        @Override public boolean isAccelEnabled() { return accelEnabled; }
        @Override public int getAccelConcurrency() { return 8; }
        @Override public int getAccelCacheMb() { return 4096; }
        @Override public int getAccelMode() { return 0; }
        @Override public String getAccelCustomHosts() { return ""; }
        @Override public boolean isHomeTopbarMessageIcon() { return false; }
        @Override public boolean isHomeTopbarMessageBadge() { return false; }
        @Override public boolean isHomeAvatarMineEntry() { return false; }
        @Override public boolean isHomeTabbarRemoveMessage() { return false; }
        @Override public boolean isHomeTabbarRemoveMine() { return false; }
        @Override public boolean isListenPauseEnabled() { return false; }
        @Override public boolean isHideTriple() { return false; }
        @Override public boolean isHideVote() { return false; }
        @Override public boolean isHideUpPrompt() { return false; }
        @Override public boolean isNoAutoRefreshEnabled() { return false; }
        @Override public boolean isLiveBgUnlockEnabled() { return false; }
        @Override public boolean isLiveTabUnlockEnabled() { return false; }
        @Override public boolean isFeedOnlyUgcEnabled() { return false; }
        @Override public boolean isFeedCleanCardEnabled() { return false; }
        @Override public boolean isFeedNoPortraitEnabled() { return false; }
        @Override public boolean isVerboseLoggingEnabled() { return false; }
        @Override public boolean isProbeEnabled() { return false; }
        @Override public String getFeedBlockedTnames() { return ""; }
    }

    /** 真实语义的假 Chain：proceed(args) 直接反射调用原方法/构造器。 */
    private static final class TChain implements XposedInterface.Chain {
        private final Executable ex;
        private final Object recv;
        private Object[] args;
        Object produced;

        TChain(Executable ex, Object recv, Object[] args) {
            this.ex = ex;
            this.recv = recv;
            this.args = args;
        }

        @Override public Object getThisObject() {
            return recv;
        }

        @Override public List<Object> getArgs() {
            return Arrays.asList(args);
        }

        @Override public Object getArg(int index) {
            return args[index];
        }

        @Override public Object proceed() throws Throwable {
            return proceed(args);
        }

        @Override public Object proceed(Object[] newArgs) throws Throwable {
            this.args = newArgs;
            try {
                Object r;
                if (ex instanceof Method) {
                    r = ((Method) ex).invoke(recv, newArgs);
                } else {
                    r = ((Constructor<?>) ex).newInstance(newArgs);
                    produced = r;
                }
                return r;
            } catch (InvocationTargetException e) {
                throw e.getCause() == null ? e : e.getCause();
            }
        }
    }
}
