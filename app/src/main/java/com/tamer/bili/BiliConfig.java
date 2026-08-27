package com.tamer.bili;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * BiliTamer 开关配置：与设置界面(SettingsActivity)共用同一份 SharedPreferences。
 * Hook 侧（libxposed）按 conf 文件读取：最高优先级为 B 站 App 数据目录下的
 * /data/data/com.bilibili.app.in/files/bili_tamer.conf（SettingsActivity 通过 root
 * 同步写入，B 站进程可读），其次 /data/local/tmp 副本。
 */
public final class BiliConfig {
    public static final String MODULE_PKG = "com.tamer.bili";
    public static final String PREFS_NAME = "bili_tamer_config";
    public static final String TARGET_PKG = "com.bilibili.app.in";
    public static final String WEB_PROCESS = "com.bilibili.app.in:web";
    public static final String CONF_NAME = "bili_tamer.conf";
    /** 实际生效的配置来源（日志排查用） */
    public static volatile String sConfSource = "defaults";
    /** 最近一次读到的本地 conf 全路径（仅日志诊断）。 */
    private static volatile String sLastConfPath = "";

    // ===== 总开关 =====
    public static final String KEY_MASTER = "master_enabled";

    // ===== IP 属地（评论区 + 用户主页）=====
    public static final String KEY_IP_LOCATION = "ip_location_enabled";
    /** 身份声明范围：0=全局（旧行为，所有请求都改写身份）；1=评论区限定（默认，仅评论/字幕 RPC 改写）。 */
    public static final String KEY_IP_SCOPE = "ip_scope_mode";
    public static final int IP_SCOPE_GLOBAL = 0;
    public static final int IP_SCOPE_COMMENT = 1;

    // ===== AI 自动字幕源 =====
    public static final String KEY_AI_SUBTITLE = "ai_subtitle_enabled";

    // ===== 播放器解码：0=自动 1=HEVC 2=AV1 =====
    public static final String KEY_CODEC = "codec_mode";

    // ===== 音质：0=默认 1=AAC 2=杜比全景声 3=Hi-Res 无损 =====
    public static final String KEY_AUDIO_QUALITY = "audio_quality";

    // ===== HDR：0=自动顺位 1=锁定 HDR 2=锁定 HDR Vivid 3=关闭 HDR =====
    public static final String KEY_HDR = "hdr_mode";

    // ===== 听视频（迷你播放器）：听完当前视频自动暂停 =====
    public static final String KEY_LISTEN_PAUSE_AFTER_END = "listen_pause_after_end";

    // ===== 隐藏视频内互动提示 =====
    public static final String KEY_HIDE_TRIPLE = "hide_triple";       // 一键三连
    public static final String KEY_HIDE_VOTE = "hide_vote";           // 投票/互动弹幕
    public static final String KEY_HIDE_UP_PROMPT = "hide_up_prompt"; // UP 提示（关注引导等）

    // ===== 首页不自动刷新 =====
    public static final String KEY_NO_AUTO_REFRESH = "no_auto_refresh";
    public static final String KEY_SHARE_QQ = "share_qq";           // 分享面板补回分享到 QQ

    // ===== 调试 =====
    public static final String KEY_DEBUG_ALIVE = "debug_alive_marker";
    public static final String KEY_VERBOSE = "verbose_log";
    /** 开发兜底开关：只被 v1.0 式本地 conf 文件识别；置 true 时该文件覆盖远程配置。 */
    public static final String KEY_DEV_OVERRIDE = "dev_override";

    public static final String[] ALL_KEYS = {
        KEY_MASTER,
        KEY_IP_LOCATION,
        KEY_IP_SCOPE,
        KEY_AI_SUBTITLE,
        KEY_CODEC,
        KEY_AUDIO_QUALITY,
        KEY_HDR,
        KEY_LISTEN_PAUSE_AFTER_END,
        KEY_HIDE_TRIPLE,
        KEY_HIDE_VOTE,
        KEY_HIDE_UP_PROMPT,
        KEY_NO_AUTO_REFRESH,
        KEY_SHARE_QQ,
        KEY_DEBUG_ALIVE,
        KEY_VERBOSE,
    };

    /** 默认值表：与 SettingsActivity 保持一致 */
    public static boolean defaultValueOf(String key) {
        if (KEY_MASTER.equals(key)) return true;
        if (KEY_IP_LOCATION.equals(key)) return true;   // v1.1 起出厂默认开
        if (KEY_AI_SUBTITLE.equals(key)) return false;
        if (KEY_HIDE_TRIPLE.equals(key)) return false;
        if (KEY_HIDE_VOTE.equals(key)) return false;
        if (KEY_HIDE_UP_PROMPT.equals(key)) return false;
        if (KEY_DEBUG_ALIVE.equals(key)) return false;
        if (KEY_VERBOSE.equals(key)) return false;
        if (KEY_NO_AUTO_REFRESH.equals(key)) return false;
        if (KEY_LISTEN_PAUSE_AFTER_END.equals(key)) return false;
        if (KEY_SHARE_QQ.equals(key)) return true;      // 分享到 QQ 出厂默认开
        return false;
    }

    public static int defaultIntOf(String key) {
        if (KEY_CODEC.equals(key)) return 0;
        if (KEY_AUDIO_QUALITY.equals(key)) return 0;
        if (KEY_HDR.equals(key)) return 0;
        if (KEY_IP_SCOPE.equals(key)) return IP_SCOPE_COMMENT; // v1.3 起默认评论区限定
        return 0;
    }

    private final Map<String, Object> map;

    private BiliConfig(Map<String, Object> map) {
        this.map = map;
    }

    /**
     * Hook 侧加载（v1.1）：
     * 1) 本地 conf 仅当含 dev_override=true 时作为开发覆盖生效；
     * 2) 否则一律用出厂默认值——设计目标是「分发版零配置开箱即用」，
     *    开关定制依赖设置页 + root 同步（开发机场景）。
     */
    public static BiliConfig loadForHook() {
        Map<String, Object> f = readConfFile();
        if (f != null && Boolean.TRUE.equals(f.get(KEY_DEV_OVERRIDE))) {
            sConfSource = "conf(dev):" + sLastConfPath;
            return new BiliConfig(f);
        }
        if (f != null && !f.isEmpty()) {
            // 存在旧格式本地 conf 但未声明 dev_override：忽略，防升级后被陈旧文件劫持
            sConfSource = "defaults(legacy-conf-ignored)";
        } else {
            sConfSource = "defaults";
        }
        return new BiliConfig(new HashMap<String, Object>());
    }

    public boolean get(String key, boolean def) {
        Object v = map.get(key);
        if (v instanceof Boolean) return ((Boolean) v).booleanValue();
        return def;
    }

    public int getInt(String key, int def) {
        Object v = map.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        return def;
    }

    private static Map<String, Object> readConfFile() {
        java.util.List<String> candidates = new java.util.ArrayList<String>();
        // 最高优先级：SettingsActivity 通过 root 同步到 B 站可读位置
        candidates.add("/data/data/" + TARGET_PKG + "/files/" + CONF_NAME);
        candidates.add("/data/local/tmp/" + CONF_NAME);
        candidates.add("/data/user/0/" + MODULE_PKG + "/files/" + CONF_NAME);
        for (String p : candidates) {
            try {
                File f = new File(p);
                if (!f.isFile() || !f.canRead()) continue;
                Map<String, Object> m = new HashMap<String, Object>();
                java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) {
                    int i = line.indexOf('=');
                    if (i <= 0) continue;
                    String k = line.substring(0, i).trim();
                    String v = line.substring(i + 1).trim();
                    if (KEY_CODEC.equals(k) || KEY_AUDIO_QUALITY.equals(k) || KEY_HDR.equals(k)
                            || KEY_IP_SCOPE.equals(k)) {
                        try { m.put(k, Integer.valueOf(v)); } catch (Throwable ignored2) {}
                    } else {
                        m.put(k, "true".equals(v));
                    }
                }
                br.close();
                if (!m.isEmpty()) {
                    sLastConfPath = p;
                    return m;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }
}