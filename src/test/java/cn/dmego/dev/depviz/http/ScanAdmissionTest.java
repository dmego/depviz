package cn.dmego.dev.depviz.http;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import cn.dmego.dev.depviz.config.ConfigStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** 在写响应时确定性注入连接断开，验证已登记任务仍会执行并释放槽位。 */
public class ScanAdmissionTest {
    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void disconnectedResponseDoesNotOrphanAnAdmittedJob() throws Exception {
        ConfigStore config = new ConfigStore(new File(tmp.getRoot(), "config.json"));
        File data = tmp.newFolder("data");
        config.dataDir(data.getPath());
        Router router = Router.testRouter(config);
        // 不存在的项目在发现阶段结束，不依赖本机 Maven 或网络。
        String missing = new File(tmp.getRoot(), "missing-project").getPath();
        for (int i = 1; i <= 2; i++) {
            try {
                router.handle(new Exchange(new BrokenResponse(missing)));
                fail("应模拟写响应 IOException");
            } catch (IOException expected) {
                assertEquals("client disconnected", expected.getMessage());
            }
            Job job = router.projects().jobRegistry().job("j" + i);
            assertNotNull("后台任务已在响应前启动", job);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (job.finishedAt == 0 && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertTrue("已启动任务必须有终态", job.finishedAt > 0);
            assertEquals("failed", job.status);
            assertNull(router.projects().jobRegistry().runningJob());
        }
        File[] dirs = new File(data, "jobs").listFiles();
        assertNotNull(dirs);
        assertEquals("响应失败后不留任务目录", 0, dirs.length);
    }

    private static final class BrokenResponse extends HttpExchange {
        private final URI uri;
        private final Headers request = new Headers();
        private final Headers response = new Headers();

        BrokenResponse(String dir) throws Exception {
            uri = new URI(null, null, "/api/scan", "dir=" + dir, null);
        }

        @Override public Headers getRequestHeaders() { return request; }
        @Override public Headers getResponseHeaders() { return response; }
        @Override public URI getRequestURI() { return uri; }
        @Override public String getRequestMethod() { return "POST"; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() { }
        @Override public InputStream getRequestBody() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream getResponseBody() { return new ByteArrayOutputStream(); }
        @Override public void sendResponseHeaders(int code, long length) throws IOException {
            throw new IOException("client disconnected");
        }
        @Override public InetSocketAddress getRemoteAddress() { return null; }
        @Override public int getResponseCode() { return -1; }
        @Override public InetSocketAddress getLocalAddress() { return null; }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) { }
        @Override public void setStreams(InputStream in, OutputStream out) { }
        @Override public HttpPrincipal getPrincipal() { return null; }
    }
}
