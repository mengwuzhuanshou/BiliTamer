package com.bilibili.lib.okdownloader.internal.spec;

import java.util.Map;

/** 替身：与真身同形 20 参构造器（arg0=url、arg19=String），记录实收 url。 */
public final class SingleSpec {
    public static final int SHAPE_ARGS = 20;
    public final String url;
    public final String tail;

    public SingleSpec(String url, String a, String b, String c, int d, String e, int f, int g,
                      int h, long i, long j, int k, int l, boolean m, String n, boolean o,
                      int p, int q, Map r, String s) {
        this.url = url;
        this.tail = s;
    }

    public String getUrl() {
        return url;
    }
}
