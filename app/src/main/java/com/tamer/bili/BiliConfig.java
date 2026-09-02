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

    // ===== 播放器解码：0=自动 1=HEVC 2=AV1 =====
    public static final String KEY_CODEC = "codec_mode";

    // ===== 播放器硬解过滤：自动顺位下按设备硬解能力过滤 HEVC/AV1（默认开）=====
    public static final String KEY_CODEC_HW_FILTER = "codec_hw_filter";

    // ===== 首页 UI 布局（国内版风格）：顶栏消息图标 / 头像=我的入口 / 底栏删 tab =====
    public static final String KEY_HOME_TOPBAR_MSG_ICON = "home_topbar_message_icon";
    public static final String KEY_HOME_AVATAR_MINE_ENTRY = "home_avatar_mine_entry";
    public static final String KEY_HOME_TABBAR_RM_MSG = "home_tabbar_remove_message";
    public static final String KEY_HOME_TABBAR_RM_MINE = "home_tabbar_remove_mine";
    public static final String KEY_HOME_TOPBAR_MSG_BADGE = "home_topbar_message_badge";

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

    // ===== 首页推荐分区屏蔽：tname 词表（逗号分隔存储；唯一字符串键）=====
    public static final String KEY_FEED_BLOCK_TNAMES = "feed_blocked_tnames";

    // ===== 调试 =====
    public static final String KEY_DEBUG_ALIVE = "debug_alive_marker";
    public static final String KEY_VERBOSE = "verbose_log";
    /** 开发兜底开关：只被 v1.0 式本地 conf 文件识别；置 true 时该文件覆盖远程配置。 */
    public static final String KEY_DEV_OVERRIDE = "dev_override";


    public static final String[] ALL_KEYS = {
        KEY_MASTER,
        KEY_IP_LOCATION,
        KEY_IP_SCOPE,
        KEY_CODEC,
        KEY_CODEC_HW_FILTER,
        KEY_AUDIO_QUALITY,
        KEY_HDR,
        KEY_HOME_TOPBAR_MSG_ICON,
        KEY_HOME_TOPBAR_MSG_BADGE,
        KEY_HOME_AVATAR_MINE_ENTRY,
        KEY_HOME_TABBAR_RM_MSG,
        KEY_HOME_TABBAR_RM_MINE,
        KEY_FEED_BLOCK_TNAMES,
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
        if (KEY_CODEC_HW_FILTER.equals(key)) return true; // v1.6.1 起出厂默认开（黑屏修复）
        if (KEY_HOME_TOPBAR_MSG_ICON.equals(key)) return true;   // v1.7.0 首页布局（6.4.0）
        if (KEY_HOME_TOPBAR_MSG_BADGE.equals(key)) return true;  // v1.7.0 顶栏消息未读角标
        if (KEY_HOME_AVATAR_MINE_ENTRY.equals(key)) return true; // v1.7.0 首页布局（6.4.0）
        if (KEY_HOME_TABBAR_RM_MSG.equals(key)) return true;     // v1.7.0 底栏删消息 tab（顶栏消息入口替代）
        if (KEY_HOME_TABBAR_RM_MINE.equals(key)) return true;    // v1.7.0 底栏隐藏「我的」tab（数据保留，顶栏头像经真实派发打开完整页）
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
     * Hook 侧加载（LineTamer v1.6.1 同款代次协议，最小权限）：
     * 读序 host-conf → module 副本 → B 站 files → /data/local/tmp，
     * 每来源带 conf_gen 代次：gen 大者胜、同代次先到先得；全无代次（旧格式）
     * 沿用静态顺序，兼容旧副本。XSharedPreferences 通道不采用（LSPosed 2.2.0
     * 标记废弃、2.3.0 移除）。LAST_CONF_SRC 带 confSrc= 便于诊断
     * （设置页开关可能根本到不了目标进程——HMT/QQTamer §13 教训）。
     */
    public static BiliConfig loadForHook() {
        java.util.List<String> paths = new java.util.ArrayList<String>();
        // ⓪ 宿主自有副本：设置页经启动 Intent 投递后由 Hook 写入宿主 files
        paths.add("/data/user/0/" + TARGET_PKG + "/files/" + HOST_CONF_NAME);
        // ①② 模块自有两份（设置页随保存实时重写，永远最新）
        paths.add("/data/user/0/" + MODULE_PKG + "/files/" + CONF_NAME);
        paths.add("/data/user/0/" + MODULE_PKG + "/shared_prefs/" + CONF_NAME);
        // ③ B 站 files（root 写，仅开发兜底）
        paths.add("/data/data/" + TARGET_PKG + "/files/" + CONF_NAME);
        // ④ /data/local/tmp（root 写，仅开发兜底，陈旧风险最高）
        paths.add("/data/local/tmp/" + CONF_NAME);
        ParsedConf best = null;
        String bestName = null;
        for (int i = 0; i < paths.size(); i++) {
            ParsedConf p = parseConfPath(paths.get(i));
            if (p == null) {
                continue;
            }
            if (best == null || (p.gen > 0 && p.gen > best.gen)) {
                best = p;
                bestName = confSrcName(i);
            }
        }
        if (best != null) {
            sConfSource = bestName + " gen=" + best.gen;
            sLastConfPath = best.path;
            return new BiliConfig(best.map);
        }
        sConfSource = "defaults";
        return new BiliConfig(new HashMap<String, Object>());
    }

    public static final String HOST_CONF_NAME = "bili_tamer_host.conf";
    public static final String KEY_CONF_GEN = "conf_gen";

    public boolean get(String key, boolean def) {
        Object v = map.get(key);
        if (v instanceof Boolean) return ((Boolean) v).booleanValue();
        return def;
    }

    /** 字符串键读取（当前仅 feed_blocked_tnames；缺失/类型不符返回空串）。 */
    public String getString(String key) {
        Object v = map.get(key);
        return v instanceof String ? (String) v : "";
    }

    public int getInt(String key, int def) {
        Object v = map.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        return def;
    }

    /** 单个 conf 来源解析结果 */
    private static final class ParsedConf {
        final Map<String, Object> map;
        final long gen;
        final String path;
        ParsedConf(Map<String, Object> map, long gen, String path) {
            this.map = map;
            this.gen = gen;
            this.path = path;
        }
    }

    private static String confSrcName(int i) {
        if (i == 0) return "host-conf";
        if (i == 1) return "module-files-conf";
        if (i == 2) return "module-prefs-conf";
        if (i == 3) return "files-conf";
        return "tmp-conf";
    }

    /** 解析单个 conf 副本：kv 行 + conf_gen 代次行（BOM 去除；非已知类型键跳过）。 */
    private static ParsedConf parseConfPath(String path) {
        try {
            File f = new File(path);
            if (!f.isFile() || !f.canRead()) return null;
            StringBuilder sb = new StringBuilder();
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            br.close();
            return parseConfText(sb.toString(), path);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** conf 全文解析：返回 kv（布尔严格 true/false；int/字符串键原样）+ 代次。 */
    private static ParsedConf parseConfText(String text, String path) {
        try {
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1); // BOM 去除（HMT §13 教训）
            }
            Map<String, Object> m = new HashMap<String, Object>();
            long gen = 0L;
            for (String line : text.split("\\r?\\n")) {
                int i = line.indexOf('=');
                if (i <= 0) {
                    continue;
                }
                String k = line.substring(0, i).trim();
                String v = line.substring(i + 1).trim();
                if (KEY_CONF_GEN.equals(k)) {
                    try { gen = Long.parseLong(v); } catch (Throwable ignored2) {}
                    continue;
                }
                if (KEY_DEV_OVERRIDE.equals(k)) {
                    continue; // 新协议下 remote/host 副本即权威，不再按 dev_override 分叉
                }
                boolean known = false;
                for (String key : ALL_KEYS) {
                    if (key.equals(k)) { known = true; break; }
                }
                if (!known) {
                    continue; // 未知键跳过（严格解析：假阳性教训）
                }
                if (KEY_CODEC.equals(k) || KEY_AUDIO_QUALITY.equals(k)
                        || KEY_HDR.equals(k) || KEY_IP_SCOPE.equals(k)) {
                    try { m.put(k, Integer.valueOf(v)); } catch (Throwable ignored2) {}
                } else if (KEY_FEED_BLOCK_TNAMES.equals(k)) {
                    m.put(k, v);
                } else if ("true".equals(v) || "false".equals(v)) {
                    m.put(k, Boolean.valueOf("true".equals(v)));
                }
            }
            if (m.isEmpty()) {
                return null;
            }
            return new ParsedConf(m, gen, path);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Hook 运行时热替换配置（启动 Intent 投递 / 主链路刷新用）：整表替换并
     * 记录代次与来源。conf 文本规范化由调用方 writeHostConf 完成。
     */
    public long overrideGen = 0L;

    public static BiliConfig fromConfText(String conf, long gen) {
        ParsedConf p = parseConfText(conf, "intent");
        if (p == null) {
            return null;
        }
        BiliConfig c = new BiliConfig(p.map);
        c.overrideGen = p.gen > 0 ? p.gen : gen;
        sConfSource = "intent gen=" + c.overrideGen;
        return c;
    }

    /** 规范化 conf 全文（按解析结果重建行，传输形态不落盘）。 */
    public String toNormalizedText(long gen) {
        StringBuilder sb = new StringBuilder();
        for (String key : ALL_KEYS) {
            Object v = map.get(key);
            if (v == null) {
                continue;
            }
            sb.append(key).append('=').append(v).append('\n');
        }
        sb.append(KEY_CONF_GEN).append('=').append(gen).append('\n');
        return sb.toString();
    }
}
