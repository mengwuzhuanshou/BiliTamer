package com.tamer.bili.accel;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/** 取字节的底层通道；抽象出来是为了能在桌面 JVM 上用假 CDN 跑自测。 */
public interface Transport {

    /**
     * 发起一次闭区间 GET。实现必须：带 Range 头、不自动跟随跨主机重定向、
     * 首字节前用 connectTimeoutMs、两次读之间用 readTimeoutMs。
     */
    Response open(String url, long start, long end, Map<String, String> headers,
                  int connectTimeoutMs, int readTimeoutMs) throws IOException;

    /** 一次响应：状态码、Content-Range 解析结果（未知时为 -1）、正文流。 */
    final class Response {
        public final int status;
        public final long rangeStart;
        public final long rangeEnd;
        public final long total;
        public final InputStream body;
        private final java.net.URLConnection connection;

        public Response(int status, long rangeStart, long rangeEnd, long total,
                        InputStream body, java.net.URLConnection connection) {
            this.status = status;
            this.rangeStart = rangeStart;
            this.rangeEnd = rangeEnd;
            this.total = total;
            this.body = body;
            this.connection = connection;
        }

        public void close() {
            try {
                if (body != null) {
                    body.close();
                }
            } catch (IOException ignored) {
                // 关闭失败无所谓：连接本身已被断开。
            }
            if (connection instanceof java.net.HttpURLConnection) {
                ((java.net.HttpURLConnection) connection).disconnect();
            }
        }
    }
}
