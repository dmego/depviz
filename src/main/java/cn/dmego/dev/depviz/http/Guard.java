package cn.dmego.dev.depviz.http;

import java.net.URI;

/**
 * Host / Origin 校验。
 *
 * <p>服务只绑回环，但浏览器里任何一个网页都能对 {@code 127.0.0.1:端口} 发请求。
 * 校验 Host 头等于本机端口、Origin（若有）也必须是本机，可以挡掉 DNS rebinding。
 */
final class Guard {

    private Guard() {
    }

    static boolean allowed(Exchange e, int port) {
        String host = e.header("Host");
        if (host == null || !isLoopbackHost(host, port)) {
            return false;
        }
        String origin = e.header("Origin");
        if (origin == null) {
            // 根本没有 Origin 头：curl、地址栏导航、同源 GET。浏览器不会在这种情况下
            // 伪造别的站点，放行。
            return true;
        }
        if ("null".equals(origin.trim())) {
            // 字面值 "null" 是 **opaque origin**，不是「没有来源」：sandbox iframe、
            // file:// 页面、data: URL 都发这个值。放行等于让任意一个本地打开的
            // HTML 文件（或嵌进来的第三方页面）改我们的配置 —— 它只要
            // `fetch('http://127.0.0.1:8080/api/config', {method:'POST',
            //  body:'{"maven":"/tmp/evil"}'})`，`text/plain` 又不触发 CORS 预检。
            // 实测真实 Chrome 能借此改掉 profiles 与 Maven 路径，所以必须拒。
            return false;
        }
        return isLoopbackOrigin(origin, port);
    }

    /**
     * Host 头必须是回环地址，且<b>端口必须对得上</b>。
     *
     * <p>只校验主机名不校验端口是不够的：同一台机器上可能同时跑着别的本地服务，
     * 一个页面若能借 {@code localhost:其他端口} 的 Host 头打到我们这里，
     * 端口隔离就形同虚设。端口对不上直接拒。
     */
    private static boolean isLoopbackHost(String host, int port) {
        String h = host;
        int colon = h.lastIndexOf(':');
        String portPart = "";
        if (colon > 0 && h.indexOf(']') < colon) {
            portPart = h.substring(colon + 1);
            h = h.substring(0, colon);
        }
        boolean loopback = "127.0.0.1".equals(h) || "localhost".equals(h)
                || "[::1]".equals(h) || "::1".equals(h);
        if (!loopback) {
            return false;
        }
        // 没写端口 = 默认 80，只有服务真跑在 80 上才放行
        int hostPort = portPart.isEmpty() ? 80 : parsePort(portPart);
        return hostPort == port;
    }

    private static int parsePort(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean isLoopbackOrigin(String origin, int port) {
        try {
            URI u = new URI(origin);
            if (!"http".equalsIgnoreCase(u.getScheme()) || u.getRawUserInfo() != null
                    || u.getRawQuery() != null || u.getRawFragment() != null
                    || (u.getRawPath() != null && !u.getRawPath().isEmpty())) {
                return false;
            }
            String h = u.getHost();
            if (!("127.0.0.1".equals(h) || "localhost".equals(h)
                    || "::1".equals(h) || "[::1]".equals(h))) {
                return false;
            }
            int originPort = u.getPort() == -1 ? 80 : u.getPort();
            return originPort == port;
        } catch (Exception ex) {
            return false;
        }
    }
}
