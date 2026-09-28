package com.bilibili.lib.okdownloader.internal.process;

import java.util.ArrayList;
import java.util.List;

/**
 * 替身：Pr0.m 事件回传方法族（b/c/e/h/n/s/u）把实收参数记进 dispatched；
 * 嵌套类 a 是 IRemoteDownloadService 桩（cancel/pause/queryProgress 记录实收形态）。
 */
public final class ProcessService {

    public final List<Object[]> dispatched = new ArrayList<Object[]>();

    public void b(String url) {
        dispatched.add(new Object[]{"b", url});
    }

    public void c(String url) {
        dispatched.add(new Object[]{"c", url});
    }

    public void e(String url, String a, String b) {
        dispatched.add(new Object[]{"e", url, a, b});
    }

    public void h(int code, String url) {
        dispatched.add(new Object[]{"h", url});
    }

    public void n(String url) {
        dispatched.add(new Object[]{"n", url});
    }

    public void s(long x, long y, String url, List parts) {
        dispatched.add(new Object[]{"s", url});
    }

    public void u(String url) {
        dispatched.add(new Object[]{"u", url});
    }

    /** 形状筛子必须放过这些：无 String / 含非法参数类型。 */
    public void onCreate() {
        dispatched.add(new Object[]{"onCreate"});
    }

    public void attachBaseContext(Object context) {
        dispatched.add(new Object[]{"attach", context});
    }

    public Object onBind(Object intent) {
        dispatched.add(new Object[]{"onBind"});
        return null;
    }

    public static final class a {
        public final List<Object[]> verbs = new ArrayList<Object[]>();

        public boolean cancel(String url) {
            verbs.add(new Object[]{"cancel", url});
            return true;
        }

        public boolean pause(String url) {
            verbs.add(new Object[]{"pause", url});
            return true;
        }

        public int queryProgress(String url) {
            verbs.add(new Object[]{"queryProgress", url});
            return 5;
        }

        public void pauseAll() {
            verbs.add(new Object[]{"pauseAll"});
        }

        public void registerCallback(Object cb, int i) {
            verbs.add(new Object[]{"register"});
        }
    }
}
