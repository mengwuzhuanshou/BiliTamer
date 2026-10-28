package com.tamer.bili.ui;

import android.content.Context;
import android.content.SharedPreferences;

import com.tamer.bili.BiliConfig;

import java.util.LinkedHashMap;

/**
 * conf 持久化/同步（v1.7.0 从 SettingsActivity 抽出，供多个设置界面共用）。
 *
 * 通道（LineTamer v1.6.1 同款最小权限方案，已实机验证）：
 *  1) 设置页保存 = SP 写入 + 本地 conf 副本（模块 files/shared_prefs，无 root）
 *     + gen 盖章（conf_gen=毫秒代次）；
 *  2) 无 root 主链路 = 组件启动投递：带 bili_conf/bili_gen/bili_nonce extras 拉起 B 站，
 *     Hook 在启动 Activity 截获后写入宿主自有 files（bili_tamer_host.conf），
 *     代次协议保证陈旧副本（root 停写后的旧件）永远盖不过新配置；
 *  3) Hook 应用成功后回一条 ACTION_CONF_ACK 广播，设置页据此把「已保存」和
 *     「宿主真的用上了」分开说——这一步以前静默失败过（见 describeAck）；
 *  4) root conf 同步仅兜底（需授权；分发版不依赖），落点先 /data/local/tmp 再尽力写
 *     宿主 files，且现在也不必再等冷启动：Hook 挂了 onResume 重读，回前台就按代次应用。
 * 读序（BiliConfig.loadForHook）：host-conf → 模块副本 → 宿主 files →
 * /data/local/tmp，gen 大者胜、同代次先到先得；路径按当前用户区与宿主包名拼。
 * /data/local/tmp 世界可写，读侧两道锁：先要求 conf_key 防伪键（BiliConfig.CONF_KEY，
 * 写侧 kvConfText 每次都带），再过代次降档——只在没有可信副本压得住它时才采纳。
 * 兜底成败不是静默的：saveAll 把这一句存进内存并写 logcat，设置页状态行原样挂出来。
 */
final class ConfSync {

    private static volatile String sLastConf = null;
    private static volatile long sLastGen = 0L;
    /** 本次投递的一次性凭据：回执只有带上同一个 nonce 才认（防其它 App 冒充广播）。 */
    private static volatile long sLastNonce = 0L;
    /** 最近一次 root 兜底的成败一句话；设置页状态行与 logcat 共用这一句。 */
    private static volatile String sLastRootSync = null;
    /** su 脚本里自证的两个标记（见 rootSyncFallback 的判据说明）。 */
    private static final String MARK_TMP = "BILIROOT_TMP_OK";
    private static final String MARK_HOST = "BILIROOT_HOST_OK";

    static volatile long ackGen = 0L;
    static volatile long ackNonce = 0L;
    static volatile String ackSrc = null;
    private static volatile android.content.BroadcastReceiver sAckReceiver;

    private ConfSync() {
    }

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(BiliConfig.PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** 汇总当前完整开关集（SP 现值 + 出厂默认补齐）。 */
    static LinkedHashMap<String, Object> collectKv(Context c) {
        LinkedHashMap<String, Object> kv = new LinkedHashMap<String, Object>();
        for (String key : BiliConfig.ALL_KEYS) {
            if (BiliConfig.KEY_CODEC.equals(key) || BiliConfig.KEY_AUDIO_QUALITY.equals(key)
                    || BiliConfig.KEY_HDR.equals(key) || BiliConfig.KEY_IP_SCOPE.equals(key)
                    || BiliConfig.KEY_ACCEL_CONCURRENCY.equals(key)
                    || BiliConfig.KEY_ACCEL_CACHE_MB.equals(key)
                    || BiliConfig.KEY_ACCEL_MODE.equals(key)) {
                kv.put(key, sp(c).getInt(key, BiliConfig.defaultIntOf(key)));
            } else if (BiliConfig.KEY_FEED_BLOCK_TNAMES.equals(key)
                    || BiliConfig.KEY_ACCEL_CUSTOM_HOSTS.equals(key)) {
                kv.put(key, sp(c).getString(key, ""));
            } else {
                kv.put(key, sp(c).getBoolean(key, BiliConfig.defaultValueOf(key)));
            }
        }
        return kv;
    }

    /** conf 全文（dev_override + 全 kv + conf_key 防伪键 + gen 盖章）。 */
    private static String kvConfText(LinkedHashMap<String, Object> kv, long gen) {
        StringBuilder sb = new StringBuilder();
        sb.append(BiliConfig.KEY_DEV_OVERRIDE).append("=true").append('\n');
        sb.append(BiliConfig.KEY_CONF_KEY).append('=').append(BiliConfig.CONF_KEY).append('\n');
        for (LinkedHashMap.Entry<String, Object> en : kv.entrySet()) {
            sb.append(en.getKey()).append('=').append(String.valueOf(en.getValue())).append('\n');
        }
        sb.append(BiliConfig.KEY_CONF_GEN).append('=').append(gen).append('\n');
        return sb.toString();
    }

    private static void writeConf(java.io.File f, byte[] data) {
        try {
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
            fos.write(data);
            fos.getFD().sync();
            fos.close();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 统一保存：SP → 本地副本 → root 兜底（尽力而为，失败不影响主链路）。
     * 返回本次代次（>0 = 保存成功，可发起启动投递）。
     *
     * 代次取「当前时刻」与「已有最大代次 + 1」的较大者，而不是直接用
     * {@code System.currentTimeMillis()}：投递侧的判据是
     * {@code incoming.overrideGen <= current} 就丢弃（陈旧副本盖不过新配置），所以
     * 系统时钟一旦回拨（换机、恢复备份、手动改时间、NTP 校正都可能），此后所有保存都会
     * 被当成陈旧件**永久**丢掉，界面每次都显示「已保存」而配置再也不进宿主。单调化把
     * 这个失效面从「永久」降为「不受时钟影响」。
     */
    static long saveAll(Context c) {
        long gen = Math.max(System.currentTimeMillis(), previousGen(c) + 1);
        byte[] data = kvConfText(collectKv(c), gen).getBytes();
        writeConf(new java.io.File(c.getFilesDir(), BiliConfig.CONF_NAME), data);
        try {
            java.io.File prefsDir = new java.io.File(c.getFilesDir().getParentFile(), "shared_prefs");
            if (prefsDir.isDirectory()) {
                writeConf(new java.io.File(prefsDir, BiliConfig.CONF_NAME), data);
            }
        } catch (Throwable ignored) {
        }
        sLastConf = new String(data);
        sLastGen = gen;
        sLastRootSync = rootSyncFallback(c, data);
        android.util.Log.i("BiliTamer", sLastRootSync); // 这一句同时进设置页状态行
        return gen;
    }

    /** 最近一次 root 兜底的成败文案（本进程内，还没保存过返回 null）。 */
    static String rootSyncStatus() {
        return sLastRootSync;
    }

    /** 已知最大代次：内存里上次保存的值，和两份本地副本里各自的 conf_gen，三者取最大。 */
    private static long previousGen(Context c) {
        long best = sLastGen;
        best = Math.max(best, readGen(new java.io.File(c.getFilesDir(), BiliConfig.CONF_NAME)));
        best = Math.max(best, readGen(new java.io.File(
                new java.io.File(c.getFilesDir().getParentFile(), "shared_prefs"),
                BiliConfig.CONF_NAME)));
        return best;
    }

    /** 读一个 conf 副本里的 {@code conf_gen}；读不到（文件不存在/无该行）返回 0。 */
    private static long readGen(java.io.File f) {
        try {
            if (!f.isFile()) {
                return 0;
            }
            byte[] buf = new byte[(int) Math.min(f.length(), 65536)];
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            int n = fis.read(buf);
            fis.close();
            if (n <= 0) {
                return 0;
            }
            String prefix = BiliConfig.KEY_CONF_GEN + "=";
            for (String line : new String(buf, 0, n).split("\n")) {
                if (line.startsWith(prefix)) {
                    return Long.parseLong(line.substring(prefix.length()).trim());
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /**
     * root 兜底（需 KSU/Magisk 对模块 app 授权）。
     *
     * 落点先 /data/local/tmp，再尽力往宿主 files 写一份：
     * 小米实测 KSU 域能在宿主 files **删**自己 Hook 建的那份，但**建不了**新文件
     * （`cat > …` Permission denied，MLS 类别继承不过去），所以原来只写宿主 files 的
     * 兜底等于没有——这正是「给了 root 也没用」那类反馈落空的地方。/data/local/tmp
     * 是 shell 的家：ksu 可写（cp + chmod 实测通），非 root adb push 落出来即 0666，
     * 宿主进程读得到；配合 Hook 侧回前台重读，写完切回前台就生效，不用冷启动。
     * 代价是这份文件本机可读、任何 root 可写，所以读侧把它降成「可信通道压不住时才用」，
     * 并且要求 {@link BiliConfig#CONF_KEY} 防伪键（这份 conf 由上面的 kvConfText 写出，天然带键）。
     *
     * @return 给用户看的一句成败：tmp 那条腿成功就报成功，宿主 files 那条腿属额外努力，
     *         只追加说明。成败判据用脚本自己 echo 的标记而不是 {@code sh} 的退出码——
     *         命令是 {@code A ; B} 形态，退出码只代表最后那条，宿主 files 建不了新文件
     *         （正常情况）会把已成功的 tmp 报成失败。每条腿再各带一个 {@code test -f}：
     *         小米实测落点若已存在成同名目录时 {@code cp FILE DIR} 会**套娃成功**（文件落进
     *         目录里、读侧永远找不到）而 cp 本身返回 0，只有 test 能区分。
     */
    private static String rootSyncFallback(Context c, byte[] data) {
        java.io.File tmp = null;
        try {
            tmp = new java.io.File(c.getFilesDir(), "bili_tamer_global.tmp");
            writeConf(tmp, data);
            if (!tmp.isFile()) {
                return "root 兜底：失败（本地暂存 conf 没写出来）";
            }
            String src = tmp.getAbsolutePath();
            String tmpLeg = "cp '" + src + "' /data/local/tmp/" + BiliConfig.CONF_NAME
                    + " && chmod 644 /data/local/tmp/" + BiliConfig.CONF_NAME
                    + " && test -f /data/local/tmp/" + BiliConfig.CONF_NAME
                    + " && echo " + MARK_TMP;
            String hostLeg = "mkdir -p /data/data/" + BiliConfig.TARGET_PKG + "/files"
                    + " && cp '" + src + "' /data/data/" + BiliConfig.TARGET_PKG + "/files/"
                    + BiliConfig.CONF_NAME
                    + " && chmod 644 /data/data/" + BiliConfig.TARGET_PKG + "/files/"
                    + BiliConfig.CONF_NAME + " && test -f /data/data/" + BiliConfig.TARGET_PKG
                    + "/files/" + BiliConfig.CONF_NAME + " && echo " + MARK_HOST;
            ProcessBuilder pb = new ProcessBuilder("su", "-c", tmpLeg + "; " + hostLeg);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            java.io.InputStream in = p.getInputStream();
            byte[] buf = new byte[256];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            in.close();
            p.waitFor();
            String log = new String(out.toByteArray()).trim();
            boolean tmpOk = log.contains(MARK_TMP);
            boolean hostOk = log.contains(MARK_HOST);
            if (tmpOk) {
                return "root 兜底：已写入 /data/local/tmp"
                        + (hostOk ? " 和宿主 files" : "（宿主 files 那一份系统不让新建，正常）");
            }
            // su 没授权 / 命令被拒都走到这：把这一步的真实输出带出来，远程取证才有的可问。
            // 两条腿的标记要摘掉（另一条腿成功时它的 echo 会混进来），换行要折平——
            // 这一句既要进状态行也要进 logcat，散成多行就谁都没法读。
            String detail = log.replace(MARK_TMP, "").replace(MARK_HOST, "").trim();
            return "root 兜底：失败（" + (detail.length() == 0
                    ? "命令没报错但落点没出现 conf 文件，exit=" + p.exitValue()
                    : detail.replaceAll("\\s+", " ")) + "）"
                    + (hostOk ? "，宿主 files 那一份倒是写成了" : "");
        } catch (Throwable t) {
            return "root 兜底：失败 " + t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            if (tmp != null) {
                tmp.delete();
            }
        }
    }

    /**
     * 无 root 主链路最后一环：以 extras 携带 conf 全文 + 代次拉起宿主。
     * 跨应用组件启动不被 OEM 拦截（本机实测 Provider/URI 授权/FUSE 全被封，
     * 唯组件启动幸存）。宿主运行中 onNewIntent 即时生效，未运行则拉起后由
     * Hook 落盘宿主自有副本。
     *
     * @return {@code null} = 带配置的 Intent 已发出；非 null = 失败原因。
     *         这一步静默失败过：它整段 {@code catch (Throwable ignored)}，而设置页在
     *         调用**之前**就显示「已保存」，于是 ROM 拦下拉起时用户看到的是「设置不生效」
     *         而不是「没投递」。返回值就是为了让这一环在界面上可见。
     */
    static String launchTargetWithConf(android.app.Activity act) {
        try {
            String conf = sLastConf;
            long gen = sLastGen;
            if (conf == null || gen <= 0) {
                gen = saveAll(act);
                conf = sLastConf;
            }
            if (conf == null || gen <= 0) {
                return "本地 conf 副本没写出来（保存这一步就失败了）";
            }
            android.content.Intent li = null;
            // 显式 ComponentName 启动：不经 PM 查询，绕开 Android 11+ 包可见性
            // （getLaunchIntentForPackage 对不可见包返回 null——模块未声明 <queries>）
            try {
                li = new android.content.Intent(android.content.Intent.ACTION_MAIN);
                li.addCategory(android.content.Intent.CATEGORY_LAUNCHER);
                li.setComponent(new android.content.ComponentName(BiliConfig.TARGET_PKG,
                        "tv.danmaku.bili.MainActivityV2")); // resolve-activity 实测 launcher
            } catch (Throwable ignored) {
            }
            if (li == null) {
                li = act.getPackageManager()
                        .getLaunchIntentForPackage(BiliConfig.TARGET_PKG);
            }
            if (li == null) {
                return "找不到 B 站的启动入口（包没装，或被系统隐藏）";
            }
            li.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
            li.putExtra("bili_conf", conf);
            li.putExtra("bili_gen", gen);
            sLastNonce = System.nanoTime() ^ (gen << 1);
            if (sLastNonce == 0L) {
                sLastNonce = 1L;
            }
            li.putExtra("bili_nonce", sLastNonce);
            armAckProbe(act);
            act.startActivity(li);
            return null;
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /**
     * 注册回执接收：运行时 receiver，只在设置页活着的时候收，不需要清单组件。
     * receiver 把回执落进 SP，所以重新打开设置页也还能看到「宿主当前用的是哪一份」。
     */
    private static void armAckProbe(android.app.Activity act) {
        try {
            if (sAckReceiver != null) {
                return;
            }
            sAckReceiver = new android.content.BroadcastReceiver() {
                @Override public void onReceive(android.content.Context c, android.content.Intent i) {
                    try {
                        ackGen = i.getLongExtra("gen", 0L);
                        ackNonce = i.getLongExtra("nonce", 0L);
                        ackSrc = i.getStringExtra("src");
                        sp(c).edit().putLong("ack_gen", ackGen)
                                .putString("ack_src", ackSrc == null ? "" : ackSrc)
                                .putLong("ack_at", System.currentTimeMillis()).apply();
                    } catch (Throwable ignored) {
                    }
                }
            };
            // 2 = RECEIVER_EXPORTED：API 33+ 注册非系统广播的 receiver 必须显式带导出标志，
            // 否则 registerReceiver 直接抛 SecurityException。
            act.registerReceiver(sAckReceiver,
                    new android.content.IntentFilter(BiliConfig.ACTION_CONF_ACK), 2);
        } catch (Throwable t) {
            sAckReceiver = null;
        }
    }

    static void disarmAckProbe(android.content.Context c) {
        try {
            if (sAckReceiver != null && c != null) {
                c.unregisterReceiver(sAckReceiver);
            }
        } catch (Throwable ignored) {
        }
        sAckReceiver = null;
    }

    /**
     * 投递结果读数：把「界面已保存」和「宿主真的用上了」分开说。
     * 拉起被 ROM 吞掉时 startActivity 并不抛异常（拦链式启动、或把 Intent 换成裸启动都
     * 是这种情况），旧文案只会显示「正在拉起 B 站同步配置」，用户于是只能读出
     * 「开关坏了」。这条读数就是为了让断点自己现形。
     */
    static String describeAck(android.content.Context c, long waitedMs) {
        long saved = sLastGen;
        if (saved <= 0) {
            saved = readGen(new java.io.File(c.getFilesDir(), BiliConfig.CONF_NAME));
        }
        long acked = sp(c).getLong("ack_gen", 0L);
        String src = sp(c).getString("ack_src", "");
        if (acked <= 0) {
            return "⚠️ 已保存 gen=" + saved + "，但这台机器上 B 站从没回报过收到。\n"
                    + "多半是系统拦了跨应用启动：在 设置→应用→B站→启动管理/关联启动 里放行"
                    + "「BiliTamer 启动 B 站」；或给模块授权 root（回前台会自动重读配置文件）；"
                    + "或先划掉 B 站再打开一次。";
        }
        if (ackNonce != 0 && ackNonce == sLastNonce) {
            return "✅ B 站已确认这次投递（gen=" + acked + "，生效来源 " + src + "）";
        }
        if (acked >= saved) {
            return "✅ 宿主正在用这份配置（gen=" + acked + "，生效来源 " + src + "）";
        }
        return "⚠️ 宿主还在用 gen=" + acked + "（来源 " + src + "），比这次保存的 gen=" + saved
                + " 旧——新配置没进宿主。请放行跨应用启动，或划掉 B 站重开。";
    }
}
