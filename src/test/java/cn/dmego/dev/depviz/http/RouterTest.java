package cn.dmego.dev.depviz.http;

import cn.dmego.dev.depviz.config.ConfigStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * HTTP 层的契约测试。
 *
 * <p>重点不是「能返回 200」，而是几件容易在重构中悄悄坏掉的事：
 * 缺参数必须是 400 而不是 500、错误响应必须是统一形状、静态资源必须能从根路径拿到。
 */
public class RouterTest extends HttpTestBase {

    @Test
    public void healthReturnsOk() throws Exception {
        Resp r = send("GET", "/api/health", null);
        assertEquals(200, r.status);
        assertTrue(r.body.contains("\"ok\":true"));
    }

    @Test
    public void missingParamsAreBadRequestNotServerError() throws Exception {
        assertEquals("DELETE /api/projects 缺 id", 400, send("DELETE", "/api/projects", null).status);
        assertEquals("GET /api/discover 缺 dir", 400, send("GET", "/api/discover", null).status);
    }

    @Test
    public void unknownJobIs404() throws Exception {
        Resp r = send("GET", "/api/scan/status?jobId=nope", null);
        assertEquals(404, r.status);
        assertTrue("错误码必须写进 body", r.body.contains("JOB_NOT_FOUND"));
    }

    @Test
    public void errorShapeIsUniform() throws Exception {
        Resp r = send("GET", "/api/scan/status?jobId=nope", null);
        assertTrue("必须有 error 字段", r.body.contains("\"error\""));
        assertTrue("必须有 message 字段", r.body.contains("\"message\""));
        assertTrue("必须有 detail 字段", r.body.contains("\"detail\""));
    }

    @Test
    public void unknownApiIs404() throws Exception {
        assertEquals(404, send("GET", "/api/nope", null).status);
    }

    @Test
    public void indexAndAssetsAreServedFromRoot() throws Exception {
        Resp idx = send("GET", "/", null);
        assertEquals("根路径必须是 index.html", 200, idx.status);
        assertTrue(idx.body.contains("<html") || idx.body.contains("<!DOCTYPE"));

        assertEquals("styles.css 相对路径要能拿到", 200, send("GET", "/styles.css", null).status);
        assertEquals("js/app.js 相对路径要能拿到", 200, send("GET", "/js/app.js", null).status);
        assertEquals("vendor 也要能拿到", 200,
                send("GET", "/vendor/cytoscape.min.js", null).status);
    }

    @Test
    public void staticPathTraversalIsBlocked() throws Exception {
        Resp r = send("GET", "/js/../../../etc/passwd", null);
        assertTrue("不能穿越到项目外，实际 " + r.status, r.status == 400 || r.status == 404);
    }

    @Test
    public void discoverReturnsModulesForRealProject() throws Exception {
        assertTrue("仓库内 demo POM 必须存在", new File(PROJ, "pom.xml").isFile());
        Resp r = send("GET", "/api/discover?dir=" + PROJ, null);
        assertEquals(200, r.status);
        assertTrue("要有 moduleCount", r.body.contains("\"moduleCount\""));
        assertTrue("要有 modules 数组", r.body.contains("\"modules\""));
    }

    @Test
    public void discoveryUsesTheSameConfiguredProfilesAsScanning() throws Exception {
        File root = tmp.newFolder("profiled");
        File child = new File(root, "child");
        assertTrue(child.mkdir());
        java.nio.file.Files.write(new File(root, "pom.xml").toPath(),
                ("<project><modelVersion>4.0.0</modelVersion><groupId>test</groupId>"
                        + "<artifactId>root</artifactId><version>1</version><packaging>pom</packaging>"
                        + "<profiles><profile><id>extra</id><modules><module>child</module></modules>"
                        + "</profile></profiles></project>").getBytes(StandardCharsets.UTF_8));
        java.nio.file.Files.write(new File(child, "pom.xml").toPath(),
                ("<project><modelVersion>4.0.0</modelVersion><groupId>test</groupId>"
                        + "<artifactId>child</artifactId><version>1</version></project>")
                        .getBytes(StandardCharsets.UTF_8));
        assertEquals(200, send("POST", "/api/config", "{\"profiles\":\"extra\"}").status);
        Resp found = send("GET", "/api/discover?dir="
                + java.net.URLEncoder.encode(root.getPath(), "UTF-8"), null);
        assertEquals(200, found.status);
        assertTrue(found.body, found.body.contains("\"moduleCount\":2"));
    }

    @Test
    public void configRoundTripKeepsShape() throws Exception {
        Resp before = send("GET", "/api/config", null);
        assertEquals(200, before.status);
        for (String k : new String[]{"maven", "javaHome", "settingsXml", "localRepo",
                "offline", "profiles", "dataDir", "xmx", "threads"}) {
            assertTrue("配置必须含键 " + k, before.body.contains("\"" + k + "\""));
        }
        Resp saved = send("POST", "/api/config", "{\"profiles\":\"a,b\",\"offline\":false}");
        assertEquals(200, saved.status);
        assertTrue(saved.body.contains("\"a,b\""));
    }

    /**
     * 端口必须校验。只认主机名的话，同机其他本地服务的 Host 头也能打进来，
     * 端口隔离形同虚设 —— 这条曾经漏过。
     *
     * <p>用裸 socket 而不是 {@link HttpURLConnection}：后者把 {@code Host}
     * 当受限头静默丢弃，测出来永远是 200，等于没测。
     */
    @Test
    public void hostPortMustMatch() throws Exception {
        int port = svc.port();
        assertEquals("端口对得上应放行", 200, rawStatus("127.0.0.1:" + port, null));
        assertEquals("端口不对必须拒绝", 403, rawStatus("127.0.0.1:" + (port + 1), null));
        assertEquals("不带端口(默认80)必须拒绝", 403, rawStatus("127.0.0.1", null));
        assertEquals("非回环必须拒绝", 403, rawStatus("evil.com:" + port, null));
    }

    /** Origin 存在时也必须是本机同端口。 */
    @Test
    public void originMustBeLoopbackSamePort() throws Exception {
        int port = svc.port();
        String host = "127.0.0.1:" + port;
        assertEquals(200, rawStatus(host, "http://127.0.0.1:" + port));
        assertEquals(403, rawStatus(host, "http://evil.com:" + port));
        assertEquals(403, rawStatus(host, "http://127.0.0.1:" + (port + 1)));
        assertEquals("省略端口只能表示 80", 403, rawStatus(host, "http://127.0.0.1"));
        assertEquals("HTTPS 不是当前 HTTP 服务的来源", 403,
                rawStatus(host, "https://127.0.0.1:" + port));
        assertEquals(403, rawStatus(host, "ftp://127.0.0.1:" + port));
        assertEquals(403, rawStatus(host, "http://user@127.0.0.1:" + port));
        assertEquals(403, rawStatus(host, "http://127.0.0.1:" + port + "/path"));
        assertEquals(200, rawStatus(host, "http://[::1]:" + port));
        assertEquals("无 Origin（curl/导航）应放行", 200, rawStatus(host, null));
    }

    /**
     * {@code Origin: null} 是 **opaque origin**，不是「没有来源」。
     *
     * <p>sandbox iframe、{@code file://} 页面、{@code data:} URL 都发这个字面值。
     * 放行等于让任意一个本地 HTML 文件改我们的配置：它只要
     * {@code fetch('http://127.0.0.1:8080/api/config', {method:'POST',
     * body:'{"maven":"/tmp/evil"}'})}，而 {@code text/plain} 不触发 CORS 预检。
     */
    @Test
    public void opaqueOriginIsRejected() throws Exception {
        int port = svc.port();
        String host = "127.0.0.1:" + port;
        assertEquals(403, rawStatus(host, "null"));
        assertEquals(403, rawStatus(host, " NULL "));
    }

    /** 发一条裸 HTTP/1.1 请求，只取状态码。 */
    private int rawStatus(String hostHeader, String origin) throws Exception {
        java.net.Socket s = new java.net.Socket("127.0.0.1", svc.port());
        try {
            StringBuilder req = new StringBuilder();
            req.append("GET /api/health HTTP/1.1\r\n");
            req.append("Host: ").append(hostHeader).append("\r\n");
            if (origin != null) {
                req.append("Origin: ").append(origin).append("\r\n");
            }
            req.append("Connection: close\r\n\r\n");
            s.getOutputStream().write(req.toString().getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            java.io.InputStream in = s.getInputStream();
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[512];
            int n;
            while ((n = in.read(b)) > 0 && buf.size() < 4096) {
                buf.write(b, 0, n);
            }
            String head = new String(buf.toByteArray(), StandardCharsets.UTF_8);
            int sp = head.indexOf(' ');
            return Integer.parseInt(head.substring(sp + 1, sp + 4).trim());
        } finally {
            s.close();
        }
    }
}
