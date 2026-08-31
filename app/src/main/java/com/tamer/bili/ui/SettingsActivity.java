package com.tamer.bili.ui;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.tamer.bili.BiliConfig;

/** 模块设置界面（纯代码 UI，无资源依赖）。v1.1：远程通道为主，root 仅开发兜底。 */
public class SettingsActivity extends Activity {

    private LinearLayout root;
    private TextView statusView;
    /** 每个 App 进程只主动试一次 root 兜底，避免普通设备反复弹 su 授权/刷失败日志。 */
    private static boolean sRootTried;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        float den = getResources().getDisplayMetrics().density;
        final int dp = Math.max(1, Math.round(den));

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scroll.setBackgroundColor(Color.parseColor("#FAFAFA"));

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp * 20, dp * 24, dp * 20, dp * 40);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("B站国际版增强 (BiliTamer)");
        title.setTextColor(Color.parseColor("#FB7299"));
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        addSpace(dp * 4);

        TextView sub = new TextView(this);
        sub.setText("目标应用：com.bilibili.app.in（国际版哔哩哔哩 6.3.0 / 6.4.0）\n"
                + "IP 属地(出厂默认开)·解码/音质顺位 等——不配置也开箱即用。\n"
                + "下方开关为个性化定制项：修改需要 root 同步一次配置。");
        sub.setTextColor(Color.parseColor("#666666"));
        sub.setTextSize(13);
        root.addView(sub);
        addSpace(dp * 16);

        addSwitch(BiliConfig.KEY_MASTER, "模块总开关", "Master switch",
                "关闭后模块完全休眠，等同未启用");

        section("IP 属地", "IP location");
        addSwitch(BiliConfig.KEY_IP_LOCATION, "评论区 + 主页显示 IP 属地", "Show IP location",
                "把请求身份改写为国内版客户端，服务端即返回评论与主页的 IP 属地字段。\n"
                + "注意：可能让评论接口返回国内版行为，建议先小范围验证");
        addRadio(BiliConfig.KEY_IP_SCOPE, "身份声明范围", "Identity scope",
                new String[]{"评论区限定（推荐：仅评论/字幕请求改身份，其余保持国际版）",
                             "全局（旧行为：所有请求都声明国内版身份）"},
                new int[]{BiliConfig.IP_SCOPE_COMMENT, BiliConfig.IP_SCOPE_GLOBAL},
                "评论区限定可避免国内版身份带来的全局副作用（如评论区外广告）；\n"
                + "主页 IP 属地走独立 REST 通道，两种模式下均生效。改后需强制停止 B 站重开。");

        section("播放器", "Player");
        addRadio(BiliConfig.KEY_CODEC, "视频解码格式", "Video codec",
                new String[]{"顺位 Auto (AV1>HEVC>H264)", "锁定 HEVC (H.265)", "锁定 AV1"},
                new int[]{0, 1, 2},
                "顺位：优先 AV1，服务端没有 AV1 就 HEVC，都没有才用 H264；\n"
                + "锁定：只使用指定编码（该编码不可用时回退到默认 H264）");
        addRadio(BiliConfig.KEY_AUDIO_QUALITY, "音质选项", "Audio quality",
                new String[]{"顺位 Auto (杜比>无损>AAC)", "锁定 AAC", "锁定杜比全景声", "锁定 Hi-Res 无损"},
                new int[]{0, 1, 2, 3},
                "顺位：优先杜比全景声，没有就 Hi-Res 无损，再没有才 AAC；\n"
                + "锁定：只用指定音质（不可用时回退 AAC）。杜比/无损需大会员账号与服务端支持");
        addRadio(BiliConfig.KEY_HDR, "HDR 画质", "HDR",
                new String[]{"顺位 Auto (HDR Vivid>HDR>SDR)", "锁定 HDR", "锁定 HDR Vivid", "关闭 HDR"},
                new int[]{0, 1, 2, 3},
                "顺位：设备支持 HDR 时自动开启（优先 HDR Vivid）；\n"
                + "锁定/关闭：强制指定 HDR 模式。需屏幕支持 HDR 才有实际效果");

        section("听视频", "Listen mode");
        addSwitch(BiliConfig.KEY_LISTEN_PAUSE_AFTER_END, "听完此视频自动暂停", "Pause when current video ends",
                "听视频/迷你播放器播完当前视频后暂停，不自动切到下一集。\n"
                + "零监听实现：仅拦截播放完成动作入口，无额外耗电");

                section("分享", "Share");
        addSwitch(BiliConfig.KEY_SHARE_QQ, "分享面板添加「分享到 QQ」", "Add Share-to-QQ entry",
                "国际版分享面板默认没有 QQ 入口；开启后补回该渠道，点击走 B 站自带\n"
                + "的 QQ 互联分享链路（弹出 QQ 分享面板选好友/群）。需已安装手机 QQ；\n"
                + "改后需强制停止 B 站重开");

        section("隐藏互动提示", "Hide in-video prompts");
        addSwitch(BiliConfig.KEY_HIDE_TRIPLE, "隐藏一键三连", "Hide triple-like",
                "隐藏点赞/投币/收藏连击动画与提示文案");
        addSwitch(BiliConfig.KEY_HIDE_VOTE, "隐藏投票", "Hide votes",
                "隐藏互动弹幕投票面板（打分弹幕/投票）");
        addSwitch(BiliConfig.KEY_HIDE_UP_PROMPT, "隐藏 UP 提示", "Hide UP prompts",
                "隐藏关注引导气泡等 UP 提示");

        section("首页", "Home");
        addSwitch(BiliConfig.KEY_NO_AUTO_REFRESH, "首页不自动刷新", "No auto refresh on home",
                "从后台/其它页面切回首页时不自动重新加载推荐流（省流量、省电）；\n"
                + "手动下拉刷新、点击 tab、首次进入不受影响");

        section("调试", "Debug");
        addSwitch(BiliConfig.KEY_DEBUG_ALIVE, "写存活标记", "Alive marker",
                "在 B 站 files 目录写 bili_tamer_alive.txt，便于无 logcat 时确认已加载");
        addSwitch(BiliConfig.KEY_VERBOSE, "详细日志", "Verbose log",
                "打印身份改写等详细日志（Tag: BiliTamer）");

        addSpace(dp * 20);
        statusView = new TextView(this);
        statusView.setTextColor(Color.parseColor("#0A7D32"));
        statusView.setTextSize(12);
        root.addView(statusView);
        addSpace(dp * 4);
        TextView foot = new TextView(this);
        foot.setText("\u26a0 本模块由 AI 生成，请自行评估风险。/ AI-generated; use at your own discretion.\n\n"
                + "首次使用：1) 在 LSPosed 启用本模块（作用域：哔哩哔哩国际版）；\n"
                + "2) 强制停止并重开 B 站即以出厂默认生效，无需任何配置或 root。\n"
                + "定制开关：root 设备会自动同步配置（dev 场景）；本地 conf 含 dev_override=true 时优先生效。");
        foot.setTextColor(Color.parseColor("#999999"));
        foot.setTextSize(12);
        root.addView(foot);

        persistAll(true);
        // 桥晚到兜底：冷启动注入稍慢时补推一次（幂等）
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                persistAll(true);
            }
        }, 1500);
    }

    private void section(String text, String textEn) {
        float den = getResources().getDisplayMetrics().density;
        int dp = Math.max(1, Math.round(den));
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#FB7299"));
        tv.setTextSize(15);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp * 8, 0, 0);
        root.addView(tv);
        TextView tve = new TextView(this);
        tve.setText(textEn);
        tve.setTextColor(Color.parseColor("#F2A9BC"));
        tve.setTextSize(11);
        tve.setPadding(0, 0, 0, dp * 8);
        root.addView(tve);
    }

    private void addSwitch(final String key, String title, String titleEn, String desc) {
        float den = getResources().getDisplayMetrics().density;
        final int dp = Math.max(1, Math.round(den));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp * 10, 0, dp * 10);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView t1 = new TextView(this);
        t1.setText(title);
        t1.setTextColor(Color.parseColor("#222222"));
        t1.setTextSize(16);
        textCol.addView(t1);

        TextView ten = new TextView(this);
        ten.setText(titleEn);
        ten.setTextColor(Color.parseColor("#AAAAAA"));
        ten.setTextSize(11);
        textCol.addView(ten);

        TextView t2 = new TextView(this);
        t2.setText(desc);
        t2.setTextColor(Color.parseColor("#888888"));
        t2.setTextSize(12);
        textCol.addView(t2);

        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(textCol, textLp);

        Switch sw = new Switch(this);
        boolean def = BiliConfig.defaultValueOf(key);
        sw.setChecked(getSp().getBoolean(key, def));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                getSp().edit().putBoolean(key, isChecked).apply();
                persistAll(false);
            }
        });
        row.addView(sw, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(row, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addRadio(final String key, String title, String titleEn,
                          final String[] labels, final int[] values, String desc) {
        float den = getResources().getDisplayMetrics().density;
        final int dp = Math.max(1, Math.round(den));

        TextView t1 = new TextView(this);
        t1.setText(title);
        t1.setTextColor(Color.parseColor("#222222"));
        t1.setTextSize(16);
        t1.setPadding(0, dp * 10, 0, 0);
        root.addView(t1);

        TextView ten = new TextView(this);
        ten.setText(titleEn);
        ten.setTextColor(Color.parseColor("#AAAAAA"));
        ten.setTextSize(11);
        root.addView(ten);

        final RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        final int def = BiliConfig.defaultIntOf(key);
        final int cur = getSp().getInt(key, def);
        for (int i = 0; i < labels.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(labels[i]);
            rb.setTextColor(Color.parseColor("#333333"));
            rb.setTextSize(14);
            rb.setId(i + 1000);
            rb.setChecked(cur == values[i]);
            group.addView(rb, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        group.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup rg, int checkedId) {
                int idx = checkedId - 1000;
                if (idx < 0 || idx >= values.length) return;
                getSp().edit().putInt(key, values[idx]).apply();
                persistAll(false);
            }
        });
        root.addView(group);

        TextView t2 = new TextView(this);
        t2.setText(desc);
        t2.setTextColor(Color.parseColor("#888888"));
        t2.setTextSize(12);
        t2.setPadding(0, 0, 0, dp * 6);
        root.addView(t2);
    }

    private void addSpace(int px) {
        View v = new View(this);
        root.addView(v, new ViewGroup.LayoutParams(1, px));
    }

    private SharedPreferences getSp() {
        return getSharedPreferences(BiliConfig.PREFS_NAME, MODE_PRIVATE);
    }

    /** 汇总当前完整开关集（SP 现值 + 出厂默认补齐）。 */
    private java.util.LinkedHashMap<String, Object> collectKv() {
        java.util.LinkedHashMap<String, Object> kv = new java.util.LinkedHashMap<String, Object>();
        for (String key : BiliConfig.ALL_KEYS) {
            if (BiliConfig.KEY_CODEC.equals(key) || BiliConfig.KEY_AUDIO_QUALITY.equals(key)
                    || BiliConfig.KEY_HDR.equals(key)) {
                kv.put(key, Integer.valueOf(getSp().getInt(key, BiliConfig.defaultIntOf(key))));
            } else {
                kv.put(key, Boolean.valueOf(getSp().getBoolean(key, BiliConfig.defaultValueOf(key))));
            }
        }
        return kv;
    }

    /** 设置页签发的 conf 携带 dev_override=true：root 同步的意图必须覆盖出厂默认。 */
    private static String kvConfText(java.util.Map<String, Object> kv) {
        StringBuilder sb = new StringBuilder();
        sb.append(BiliConfig.KEY_DEV_OVERRIDE).append("=true").append('\n');
        for (java.util.Map.Entry<String, Object> en : kv.entrySet()) {
            sb.append(en.getKey()).append('=').append(String.valueOf(en.getValue())).append('\n');
        }
        return sb.toString();
    }

    /**
     * v1.1 主持久化：远程通道必写（无 root 主链路）；
     * root 本地副本仅在「远程未就绪」或「详细日志(开发)」时尝试，且每进程至多一次。
     */
    /**
     * 本地 root 同步（开发/授权场景）。无 root 时仅提示：
     * 出厂默认已让模块核心能力开箱即用，定制开关需授权后同步。
     */
    private void persistAll(boolean auto) {
        java.util.LinkedHashMap<String, Object> kv = collectKv();

        int rcRoot = Integer.MIN_VALUE;
        if (!sRootTried || getSp().getBoolean(BiliConfig.KEY_VERBOSE, false)) {
            sRootTried = true;
            rcRoot = rootSyncLegacy(kv);
        }

        StringBuilder st = new StringBuilder("状态：本地副本");
        if (rcRoot == Integer.MIN_VALUE) {
            st.append("：本次未尝试");
        } else if (rcRoot == 0) {
            st.append("=✅ 已同步（root）");
        } else {
            st.append("=❌ 无 root 权限——出厂默认继续生效，开关定制暂不可用");
        }
        st.append("\nconf 探针见 B 站启动日志首行 confSrc=");
        statusView.setText(st.toString());
        android.util.Log.i("BiliTamer", "persistAll: rcRoot=" + rcRoot + " auto=" + auto);

        if (!auto) {
            String msg;
            if (rcRoot == 0) msg = "已保存，强制停止 B 站重开后生效";
            else msg = "已保存到本应用；切换实际生效需 root 同步成功（当前无权限）";
            Toast.makeText(SettingsActivity.this, msg, Toast.LENGTH_SHORT).show();
        }
    }

    /** 开发兜底：按 v1.0 三副本写本地 conf（不含 dev_override，正常不会劫持分发版）。 */
    private int rootSyncLegacy(java.util.Map<String, Object> kv) {
        try {
            makeWorldReadable();
        } catch (Throwable ignored) {
        }
        byte[] data;
        try {
            data = kvConfText(kv).getBytes("UTF-8");
        } catch (Throwable e) {
            return -200;
        }
        try {
            writeConf(new java.io.File(getFilesDir(), BiliConfig.CONF_NAME), data);
        } catch (Throwable ignored) {
        }
        try {
            java.io.File prefsDir = new java.io.File(getFilesDir().getParentFile(), "shared_prefs");
            if (prefsDir.isDirectory()) {
                writeConf(new java.io.File(prefsDir, BiliConfig.CONF_NAME), data);
            }
        } catch (Throwable ignored) {
        }
        int worst;
        try {
            java.io.File tmp = new java.io.File(getFilesDir(), "bili_tamer_global.tmp");
            writeConf(tmp, data);
            int r1 = suRun("cat '" + tmp.getAbsolutePath() + "' > /data/local/tmp/"
                    + BiliConfig.CONF_NAME + " && chmod 644 /data/local/tmp/" + BiliConfig.CONF_NAME);
            int r2 = suRun("mkdir -p /data/data/" + BiliConfig.TARGET_PKG + "/files && cat '"
                    + tmp.getAbsolutePath() + "' > /data/data/" + BiliConfig.TARGET_PKG
                    + "/files/" + BiliConfig.CONF_NAME
                    + " && chmod 644 /data/data/" + BiliConfig.TARGET_PKG + "/files/" + BiliConfig.CONF_NAME);
            worst = Math.min(r1, r2);   // 任一非 0 即视为本地通道失败
            android.util.Log.i("BiliTamer", "root legacy sync rc_tmp=" + r1 + " rc_bili=" + r2);
        } catch (Throwable t) {
            android.util.Log.e("BiliTamer", "root legacy sync ex: " + t);
            worst = -300;
        }
        return worst;
    }

    private static int suRun(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
            pb.redirectErrorStream(true);
            Process prc = pb.start();
            java.io.InputStream in = prc.getInputStream();
            byte[] buf = new byte[256];
            while (in.read(buf) != -1) { /* drain */ }
            in.close();
            return prc.waitFor();
        } catch (Throwable t) {
            return -400;
        }
    }


    private void writeConf(java.io.File f, byte[] data) throws Exception {
        java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
        try {
            fos.write(data);
        } finally {
            fos.close();
        }
    }

    private void makeWorldReadable() {
        try {
            java.io.File dir = getFilesDir();
            if (dir != null && dir.exists()) {
                dir.setExecutable(true, false);
                dir.setReadable(true, false);
                java.io.File f = new java.io.File(dir, BiliConfig.CONF_NAME);
                if (f.exists()) {
                    f.setReadable(true, false);
                    f.setExecutable(true, false);
                }
            }
        } catch (Throwable ignored) {}
    }
}