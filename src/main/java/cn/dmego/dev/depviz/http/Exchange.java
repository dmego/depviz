package cn.dmego.dev.depviz.http;

import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** {@link HttpExchange} 的薄封装：查参、读体、写 JSON、错误码。 */
public final class Exchange {

    private final HttpExchange ex;
    private final Map<String, String> query;

    Exchange(HttpExchange ex) {
        this.ex = ex;
        this.query = parseQuery(ex.getRequestURI().getRawQuery());
    }

    public String method() {
        return ex.getRequestMethod().toUpperCase();
    }

    public String path() {
        return ex.getRequestURI().getPath();
    }

    /** 原始查询串（不含 {@code ?}）；没有查询参数时返回空串。日志与排查用。 */
    public String rawQuery() {
        String q = ex.getRequestURI().getRawQuery();
        return q == null ? "" : q;
    }

    /**
     * 取一个查询参数。**永远不返回 null** —— 缺参数时返回空串。
     *
     * <p>这条保证是有意的：handler 里到处是 {@code q("x").isEmpty()}，
     * 一旦可能返回 null，少传一个参数就变成 500 而不是 400。缺参数该由 handler
     * 判空串后自己报错。
     */
    public String q(String name) {
        String v = query.get(name);
        return v == null ? "" : v;
    }

    public String q(String name, String dflt) {
        String v = query.get(name);
        return v == null ? dflt : v;
    }

    public boolean qBool(String name, boolean dflt) {
        String v = query.get(name);
        if (v == null) {
            return dflt;
        }
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    public int qInt(String name, int dflt) {
        String v = query.get(name);
        if (v == null || v.isEmpty()) {
            return dflt;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    /** 请求头（用于 Host / Origin 校验）。 */
    public String header(String name) {
        return ex.getRequestHeaders().getFirst(name);
    }

    /** 设置响应头（gzip 需要先声明 {@code Content-Encoding} 再写 body）。 */
    public void header(String name, String value) {
        ex.getResponseHeaders().set(name, value);
    }

    public String body() throws IOException {
        InputStream in = ex.getRequestBody();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0 && total < 1024 * 1024) {
            out.write(buf, 0, n);
            total += n;
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    public void json(int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        send(status, bytes);
    }

    public void text(int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        send(status, bytes);
    }

    public void bytes(int status, String contentType, byte[] data) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        // 和 json() 一样不许缓存。图 JSON 是「扫完一次就不变」的，浏览器据此
        // 启发式缓存是合情合理的 —— 于是用户重扫之后刷新页面看到的还是旧图，
        // 而且没有任何办法从界面上让浏览器放手。
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        send(status, data);
    }

    /** 统一错误响应：{@code {error,message,detail}}。 */
    public void error(int status, String code, String message) throws IOException {
        json(status, "{\"error\":" + quote(code) + ",\"message\":" + quote(message)
                + ",\"detail\":\"\"}");
    }

    /** 只回状态码，不带 body（{@code 304 Not Modified} 用）。 */
    public void status(int status) throws IOException {
        ex.sendResponseHeaders(status, -1);
        ex.close();
    }

    private void send(int status, byte[] bytes) throws IOException {
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            OutputStream out = ex.getResponseBody();
            try {
                out.write(bytes);
            } finally {
                out.close();
            }
        }
        ex.close();
    }

    static String quote(String s) {
        return cn.dmego.dev.depviz.json.JsonOut.quote(s == null ? "" : s);
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        if (raw == null || raw.isEmpty()) {
            return m;
        }
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                m.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
            } catch (Exception ignored) {
                // 非法编码的参数直接丢弃
            }
        }
        return m;
    }
}
