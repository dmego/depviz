package cn.dmego.dev.depviz.http;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 地址文件：{@code depviz.sh start} 唯一的「起来没有」判据。
 *
 * <p>为什么不能靠 stdout：Windows 上 {@code System.out} 用控制台代码页（中文系统
 * GBK），脚本按 UTF-8 去 grep 中文标记永远匹配不到，于是进程明明起来了，
 * start 还是等满超时再报「没看到启动完成的标志」。这个文件只有 ASCII。
 */
public class UrlFileTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void createsMissingParentDirectories() throws Exception {
        // 脚本传的是 dist/logs/depviz.url，logs 可能还没建（首次运行、
        // 或者用户把 dist 目录整个复制走后只留了 jar 与脚本）。
        File target = new File(new File(tmp.getRoot(), "logs"), "depviz.url");
        assertTrue("前提：父目录一开始不存在", !target.getParentFile().exists());

        HttpService.writeUrlFile(target, "http://127.0.0.1:8080");

        assertTrue("父目录必须被创建", target.getParentFile().isDirectory());
        assertEquals("http://127.0.0.1:8080\n", content(target));
    }

    @Test
    public void overwritesStaleUrlFromPreviousRun() throws Exception {
        // 上一轮留下的地址必须先被盖掉：脚本读到旧端口就会报一个打不开的地址。
        File target = tmp.newFile("depviz.url");
        Files.write(target.toPath(), "http://127.0.0.1:8080\n".getBytes(StandardCharsets.UTF_8));

        HttpService.writeUrlFile(target, "http://127.0.0.1:8081");

        assertEquals("http://127.0.0.1:8081\n", content(target));
    }

    @Test
    public void writeFailureDoesNotAbortStartup() throws Exception {
        // 父路径是个普通文件时 mkdirs 必然失败。此时只能记一条 warn：
        // 地址文件是辅助产物，不该让它把一个已经绑好端口的服务拖死。
        File blocker = tmp.newFile("not-a-dir");
        File target = new File(blocker, "depviz.url");

        HttpService.writeUrlFile(target, "http://127.0.0.1:8080");

        assertTrue("写失败不应留下半截文件", !target.exists());
    }

    @Test
    public void matchesUrlReportedByRunningService() throws Exception {
        // 端口可能是 8080 被占后退到的 8081，也可能是系统随机（:0 分支），
        // 所以必须用服务真实的 baseUrl()，而不是脚本传进来的 --port。
        // 用 testRouter + 临时 config：defaultRouter() 会读写用户真实的 ~/.depviz。
        HttpService svc = HttpService.startTest(
                Router.testRouter(new cn.dmego.dev.depviz.config.ConfigStore(
                        new File(tmp.getRoot(), "config.json"))));
        try {
            File target = new File(tmp.getRoot(), "depviz.url");
            HttpService.writeUrlFile(target, svc.baseUrl());

            assertEquals("http://127.0.0.1:" + svc.port() + "\n", content(target));
        } finally {
            svc.stop();
        }
    }

    private static String content(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
