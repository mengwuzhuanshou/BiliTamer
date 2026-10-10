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
 *  2) 无 root 主链路 = 组件启动投递：带 bili_conf/bili_gen extras 拉起 B 站，
 *     Hook 在启动 Activity 截获后写入宿主自有 files（bili_tamer_host.conf），
 *     代次协议保证陈旧副本（root 停写后的旧件）永远盖不过新配置；
 *  3) root conf 同步仅开发兜底（需 KSU 授权；分发版不依赖）。
 * 读序（BiliConfig.loadForHook）：host-conf → module 副本 → B 站 files →
 * /data/local/tmp，gen 大者胜、同代次先到先得。
 */
final class ConfSync {

    private static volatile String sLastConf = null;
    private static volatile long sLastGen = 0L;

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

    /** conf 全文（dev_override + 全 kv + gen 盖章）。 */
    private static String kvConfText(LinkedHashMap<String, Object> kv, long gen) {
        StringBuilder sb = new StringBuilder();
        sb.append(BiliConfig.KEY_DEV_OVERRIDE).append("=true").append('\n');
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
        rootSyncFallback(c, data); // 开发兜底；分发版无 root 时静默失败，无害
        return gen;
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

    /** root 同步（仅开发兜底，需 KSU 对模块 app 授权；失败静默）。 */
    private static void rootSyncFallback(Context c, byte[] data) {
        try {
            java.io.File tmp = new java.io.File(c.getFilesDir(), "bili_tamer_global.tmp");
            writeConf(tmp, data);
            ProcessBuilder pb = new ProcessBuilder("su", "-c",
                    "mkdir -p /data/data/" + BiliConfig.TARGET_PKG + "/files && cat '"
                            + tmp.getAbsolutePath() + "' > /data/data/" + BiliConfig.TARGET_PKG
                            + "/files/" + BiliConfig.CONF_NAME
                            + " && chmod 644 /data/data/" + BiliConfig.TARGET_PKG
                            + "/files/" + BiliConfig.CONF_NAME);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            java.io.InputStream in = p.getInputStream();
            byte[] buf = new byte[256];
            while (in.read(buf) != -1) { /* drain */ }
            in.close();
            p.waitFor();
            tmp.delete();
        } catch (Throwable ignored) {
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
            act.startActivity(li);
            return null;
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }
}
