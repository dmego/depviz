package cn.dmego.dev.depviz.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPOutputStream;

/**
 * 大响应按需 gzip。
 *
 * <p>{@code /api/graph} 对 agentscope-java 是 2046489 B，gzip 后 165 KB —— 差 12 倍。
 * 只压超过 {@link #THRESHOLD} 的响应，小 JSON 压缩反而更大。
 */
final class Gzip {

    /** 低于这个字节数不压（压缩头和字典开销会亏本）。 */
    static final int THRESHOLD = 2048;

    private Gzip() {
    }

    /**
     * 客户端接受 gzip 且内容够大就压，否则原样返回。
     *
     * @return 已压或未压的字节
     */
    static byte[] maybe(Exchange e, String body) throws IOException {
        byte[] raw = body.getBytes("UTF-8");
        if (raw.length < THRESHOLD || !acceptsGzip(e)) {
            return raw;
        }
        return respond(e, raw, compress(raw));
    }

    /**
     * 已经预压好的版本：{@code gzipped} 为 {@code null} 时现压。
     *
     * <p>给 {@code /api/graph} 用 —— 2.0 MB 的图 JSON 在两次扫描之间不变，
     * 压一次存在 {@code ProjectState.graphGzip} 里，省掉每请求 20 ms CPU。
     */
    static byte[] maybe(Exchange e, byte[] raw, byte[] gzipped) throws IOException {
        if (raw.length < THRESHOLD || !acceptsGzip(e)) {
            return raw;
        }
        return respond(e, raw, gzipped != null ? gzipped : compress(raw));
    }

    /** 不带请求上下文的纯压缩，供扫描结束时预计算。 */
    static byte[] compress(byte[] raw) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(raw.length / 4);
        GZIPOutputStream gz = new GZIPOutputStream(buf);
        try {
            gz.write(raw);
        } finally {
            gz.close();
        }
        return buf.toByteArray();
    }

    /**
     * 预计算用：压不动就返回 {@code null}，让请求时回退到现压。
     *
     * <p>扫描结束时的压缩失败没有任何补救价值 —— 图已经解析出来了，
     * 为了一次压缩异常把整个扫描判失败是本末倒置。
     */
    static byte[] compressQuietly(byte[] raw) {
        try {
            return compress(raw);
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean acceptsGzip(Exchange e) {
        String accept = e.header("Accept-Encoding");
        return accept != null && accept.contains("gzip");
    }

    private static byte[] respond(Exchange e, byte[] raw, byte[] gz) {
        // 压缩不一定变小（已经压过的小 JSON、极端重复的内容偶尔会反向膨胀）。
        // 头必须跟着实际发出去的字节走：先声明 gzip 再返回明文，浏览器会拿
        // gzip 去解一段普通 JSON，直接报解码错误 —— 而服务端日志一切正常。
        if (gz.length >= raw.length) {
            return raw;
        }
        e.header("Content-Encoding", "gzip");
        // 同一个 URL 对不同的 Accept-Encoding 返回不同字节，必须声明
        e.header("Vary", "Accept-Encoding");
        return gz;
    }
}
