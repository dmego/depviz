package cn.dmego.dev.depviz.http;

import cn.dmego.dev.depviz.config.ConfigStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 起一个只监听回环的测试服务 + 一组发请求的小工具。
 *
 * <p>拆出来的原因：原先 {@code RouterTest} 一个类 431 行，超过「文件 ≤300 行」的硬约束。
 * 这里只放脚手架，{@link RouterTest} 管 HTTP 契约，{@link RouterScanTest} 管扫描的并发与状态机。
 */
abstract class HttpTestBase {

    /** 仓库内的单模块 Maven 项目，用于验证 HTTP 扫描状态机与并发。 */
    static final String PROJ = new File("src/test/resources/projects/demo").getAbsolutePath();

    HttpService svc;
    String base;

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @Before
    public void setUp() throws Exception {
        // 必须用临时目录：Router.defaultRouter() 会读写用户真实的 ~/.depviz/config.json。
        // dataDir 也要一起改 —— 只改 config.json 的位置是不够的：ConfigStore 的
        // dataDir 默认是 ~/.depviz，于是扫描成功的用例会把图写到
        // ~/.depviz/<项目名>/graph.json，跑一遍测试就在用户主目录里留下垃圾，
        // 而且还会让「最近打开」里凭空多出一条 fixture 项目。
        File dataDir = tmp.newFolder("data");
        ConfigStore store = new ConfigStore(new File(tmp.getRoot(), "config.json"));
        store.dataDir(dataDir.getAbsolutePath());
        svc = HttpService.startTest(Router.testRouter(store));
        base = svc.baseUrl();
    }

    @After
    public void tearDown() {
        if (svc != null) {
            svc.stop();
        }
    }

    /** 一次请求的原始结果：状态码 + 正文。 */
    static final class Resp {
        int status;
        String body;

        @Override
        public String toString() {
            return status + " " + body;
        }
    }

    Resp send(String method, String path, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(5000);
        c.setReadTimeout(30000);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        }
        Resp r = new Resp();
        r.status = c.getResponseCode();
        InputStream in = r.status >= 400 ? c.getErrorStream() : c.getInputStream();
        r.body = readAll(in);
        c.disconnect();
        return r;
    }

    static String readAll(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
