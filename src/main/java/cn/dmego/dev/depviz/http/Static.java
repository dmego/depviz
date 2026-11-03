package cn.dmego.dev.depviz.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 静态资源。全部打进 jar 的 {@code /static/} 下，浏览器直接取。
 *
 * <p>只有 8 个文件 + 3 个 vendored UMD，没有任何构建步骤（决策 D6）。
 */
final class Static {

    private static final Map<String, String> TYPES = new HashMap<String, String>();

    static {
        TYPES.put("html", "text/html; charset=utf-8");
        TYPES.put("js", "application/javascript; charset=utf-8");
        TYPES.put("css", "text/css; charset=utf-8");
        TYPES.put("svg", "image/svg+xml");
        TYPES.put("json", "application/json; charset=utf-8");
        TYPES.put("ico", "image/x-icon");
    }

    private Static() {
    }

    /**
     * {@code index.html} 里用的是相对路径（{@code styles.css}、{@code js/app.js}），
     * 从 {@code /} 打开时这些请求落在根上，不是 {@code /static/} 下。两条前缀都接受。
     */
    static void handle(Exchange e, String path) throws IOException {
        String name;
        if ("/".equals(path) || "/index.html".equals(path)) {
            name = "index.html";
        } else if (path.startsWith("/static/")) {
            name = path.substring("/static/".length());
        } else {
            name = path.substring(1);
        }
        if (name.isEmpty()) {
            name = "index.html";
        }
        if (name.contains("..")) {
            e.error(404, "NOT_FOUND", "非法路径");
            return;
        }
        byte[] data = read("/static/" + name);
        if (data == null) {
            e.error(404, "NOT_FOUND", "静态资源不存在: " + name);
            return;
        }
        // 每次打开页面都要重传 373KB 的 cytoscape + 283KB 的 dagre，本地磁盘完全
        // 读得起，但白白拖慢首屏。资源只随 jar 变（无构建步骤），用 ETag 让浏览器
        // 复用；`no-cache` 表示「可以存，但每次要回来问一句」—— 升级 jar 后立刻生效，
        // 不会出现「改了 JS 但浏览器还在用旧的」。
        String etag = "\"" + data.length + "-" + Integer.toHexString(java.util.Arrays.hashCode(data)) + "\"";
        e.header("ETag", etag);
        e.header("Cache-Control", "no-cache");
        if (etag.equals(e.header("If-None-Match"))) {
            e.status(304);
            return;
        }
        e.bytes(200, typeOf(name), data);
    }

    private static String typeOf(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
        String t = TYPES.get(ext);
        return t == null ? "application/octet-stream" : t;
    }

    private static byte[] read(String res) {
        InputStream in = Static.class.getResourceAsStream(res);
        if (in == null) {
            return null;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (IOException ex) {
            return null;
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
    }
}
