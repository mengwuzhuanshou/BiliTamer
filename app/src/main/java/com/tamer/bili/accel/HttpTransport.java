package com.tamer.bili.accel;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/** 默认通道：HttpURLConnection（不引入第三方依赖，dex 体积零增长）。 */
public final class HttpTransport implements Transport {

    /** 最多跟随几跳重定向：B 站部分节点会 302 到同路径的真实机器。 */
    private static final int MAX_REDIRECTS = 2;

    @Override
    public Response open(String url, long start, long end, Map<String, String> headers,
                         int connectTimeoutMs, int readTimeoutMs) throws IOException {
        String current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setRequestMethod("GET");
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestProperty("Range", end < 0L ? "bytes=" + start + "-" : "bytes=" + start + "-" + end);
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("Connection", "keep-alive");
            if (headers != null) {
                synchronized (headers) {
                    for (Map.Entry<String, String> entry : headers.entrySet()) {
                        String name = entry.getKey();
                        if (name == null || "range".equalsIgnoreCase(name)
                                || "accept-encoding".equalsIgnoreCase(name)) {
                            continue;
                        }
                        conn.setRequestProperty(name, entry.getValue());
                    }
                }
            }
            int status = conn.getResponseCode();
            if ((status == 301 || status == 302 || status == 303 || status == 307 || status == 308)
                    && hop < MAX_REDIRECTS) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null || location.isEmpty()) {
                    throw new IOException("redirect without Location (HTTP " + status + ")");
                }
                current = new URL(new URL(current), location).toString();
                continue;
            }
            long[] contentRange = RangeCore.parseContentRange(conn.getHeaderField("Content-Range"));
            long rangeStart = contentRange == null ? -1L : contentRange[0];
            long rangeEnd = contentRange == null ? -1L : contentRange[1];
            long total = contentRange == null ? -1L : contentRange[2];
            InputStream body;
            try {
                body = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            } catch (IOException e) {
                conn.disconnect();
                throw e;
            }
            if (body == null) {
                // 4xx 常常没有正文：给个空流，避免上层读到 null。
                body = new java.io.ByteArrayInputStream(new byte[0]);
            }
            return new Response(status, rangeStart, rangeEnd, total, body, conn);
        }
        throw new IOException("too many redirects");
    }
}
