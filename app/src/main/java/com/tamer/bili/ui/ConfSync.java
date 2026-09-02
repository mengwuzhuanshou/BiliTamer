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
                    || BiliConfig.KEY_HDR.equals(key)) {
                kv.put(key, sp(c).getInt(key, BiliConfig.defaultIntOf(key)));
            } else if (BiliConfig.KEY_FEED_BLOCK_TNAMES.equals(key)) {
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
     */
    static long saveAll(Context c) {
        long gen = System.currentTimeMillis();
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
     */
    static void launchTargetWithConf(android.app.Activity act) {
        try {
            String conf = sLastConf;
            long gen = sLastGen;
            if (conf == null || gen <= 0) {
                gen = saveAll(act);
                conf = sLastConf;
            }
            if (conf == null || gen <= 0) {
                return;
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
                return;
            }
            li.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
            li.putExtra("bili_conf", conf);
            li.putExtra("bili_gen", gen);
            act.startActivity(li);
        } catch (Throwable ignored) {
        }
    }
}
