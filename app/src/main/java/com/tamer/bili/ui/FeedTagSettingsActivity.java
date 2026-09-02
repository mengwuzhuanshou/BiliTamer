package com.tamer.bili.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.tamer.bili.BiliConfig;

import java.util.ArrayList;

/**
 * 首页推荐分区屏蔽词管理（纯代码 UI，无资源依赖）。
 *  - 批量输入：逗号（中英文）、分号、顿号、换行均可分隔，自动去重去空；
 *  - 检索定位：顶部搜索框实时过滤下方词列表；
 *  - 逐词移除：每词一行带「移除」按钮，即时保存同步。
 * 保存 = 写模块 SP + root 同步 conf（与主设置页同一 ConfSync 链路），
 * B 站侧强停重开后生效。
 */
public class FeedTagSettingsActivity extends Activity {

    private LinearLayout listBox;
    private EditText searchView;
    private EditText bulkView;
    private TextView countView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        float den = getResources().getDisplayMetrics().density;
        final int dp = Math.max(1, Math.round(den));

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scroll.setBackgroundColor(Color.parseColor("#FAFAFA"));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp * 20, dp * 24, dp * 20, dp * 40);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("分区屏蔽词管理");
        title.setTextColor(Color.parseColor("#FB7299"));
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        TextView sub = new TextView(this);
        sub.setText("推荐卡的分区（tname）包含任一词即整卡不再进入推荐列表。\n"
                + "匹配不区分词形（包含即中）：加「游戏」会连带「主机游戏」等。\n"
                + "词数无上限；改后需强制停止 B 站重开生效。");
        sub.setTextColor(Color.parseColor("#666666"));
        sub.setTextSize(12);
        sub.setPadding(0, dp * 6, 0, 0);
        root.addView(sub);

        countView = new TextView(this);
        countView.setTextColor(Color.parseColor("#0A7D32"));
        countView.setTextSize(13);
        countView.setTypeface(Typeface.DEFAULT_BOLD);
        countView.setPadding(0, dp * 10, 0, dp * 4);
        root.addView(countView);

        // ===== 批量编辑 =====
        TextView bulkHead = new TextView(this);
        bulkHead.setText("批量编辑（逗号 / 换行分隔，保存覆盖全部词表）");
        bulkHead.setTextColor(Color.parseColor("#222222"));
        bulkHead.setTextSize(14);
        bulkHead.setTypeface(Typeface.DEFAULT_BOLD);
        bulkHead.setPadding(0, dp * 8, 0, dp * 4);
        root.addView(bulkHead);

        bulkView = new EditText(this);
        bulkView.setMinLines(4);
        bulkView.setGravity(Gravity.TOP);
        bulkView.setTextSize(14);
        bulkView.setTextColor(Color.parseColor("#222222"));
        root.addView(bulkView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button bulkSave = new Button(this);
        bulkSave.setText("保存全部词表");
        bulkSave.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ArrayList<String> words = parseWords(bulkView.getText().toString());
                saveWords(words);
                Toast.makeText(FeedTagSettingsActivity.this,
                        "已保存 " + words.size() + " 个词，强停 B 站重开后生效",
                        Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(bulkSave, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ===== 检索 + 逐词移除 =====
        TextView listHead = new TextView(this);
        listHead.setText("词表（检索定位 / 逐词移除）");
        listHead.setTextColor(Color.parseColor("#222222"));
        listHead.setTextSize(14);
        listHead.setTypeface(Typeface.DEFAULT_BOLD);
        listHead.setPadding(0, dp * 12, 0, dp * 4);
        root.addView(listHead);

        searchView = new EditText(this);
        searchView.setHint("输入关键词检索定位…");
        searchView.setTextSize(14);
        searchView.setSingleLine(true);
        root.addView(searchView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        searchView.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c2) {
            }

            @Override public void onTextChanged(CharSequence s, int a, int b, int c2) {
            }

            @Override public void afterTextChanged(Editable s) {
                refreshList();
            }
        });

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(listBox, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        refreshAll();
    }

    private ArrayList<String> loadWords() {
        return parseWords(ConfSync.sp(this)
                .getString(BiliConfig.KEY_FEED_BLOCK_TNAMES, ""));
    }

    /** 逗号（中英文）/分号/顿号/换行均可分隔；去空、去重、保序。 */
    private static ArrayList<String> parseWords(String raw) {
        ArrayList<String> out = new ArrayList<String>();
        if (raw == null) {
            return out;
        }
        for (String w : raw.split("[，,;；、\\r\\n]+")) {
            String t = w.trim();
            if (t.length() > 0 && !out.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    private void saveWords(ArrayList<String> words) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(words.get(i));
        }
        ConfSync.sp(this).edit()
                .putString(BiliConfig.KEY_FEED_BLOCK_TNAMES, sb.toString()).apply();
        long gen = ConfSync.saveAll(this);
        if (gen > 0) {
            ConfSync.launchTargetWithConf(this); // 无 root 主链路：带配置拉起 B 站
        } else {
            Toast.makeText(this, "⚠ 保存失败", Toast.LENGTH_LONG).show();
        }
    }

    private void refreshAll() {
        ArrayList<String> words = loadWords();
        if (bulkView.getText().length() == 0) {
            bulkView.setText(joinLines(words));
        }
        refreshList();
    }

    private static String joinLines(ArrayList<String> words) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(words.get(i));
        }
        return sb.toString();
    }

    private void refreshList() {
        ArrayList<String> words = loadWords();
        countView.setText("当前 " + words.size() + " 个词");
        String q = searchView.getText().toString().trim();
        listBox.removeAllViews();
        float den = getResources().getDisplayMetrics().density;
        final int dp = Math.max(1, Math.round(den));
        int shown = 0;
        for (final String w : words) {
            if (q.length() > 0 && !w.toLowerCase().contains(q.toLowerCase())) {
                continue;
            }
            shown++;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp * 4, 0, dp * 4);
            TextView tv = new TextView(this);
            tv.setText(w);
            tv.setTextColor(Color.parseColor("#222222"));
            tv.setTextSize(15);
            row.addView(tv, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            Button del = new Button(this);
            del.setText("移除");
            del.setTextSize(12);
            del.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    ArrayList<String> rest = loadWords();
                    rest.remove(w);
                    saveWords(rest);
                    refreshList();
                    bulkView.setText(joinLines(rest));
                }
            });
            row.addView(del, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            listBox.addView(row, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (shown == 0) {
            TextView empty = new TextView(this);
            empty.setText(words.isEmpty() ? "（词表为空，用上方批量编辑添加）"
                    : "（无匹配词）");
            empty.setTextColor(Color.parseColor("#999999"));
            empty.setTextSize(13);
            empty.setPadding(0, dp * 8, 0, 0);
            listBox.addView(empty);
        }
    }
}
