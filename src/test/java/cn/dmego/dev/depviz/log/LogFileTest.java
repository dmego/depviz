package cn.dmego.dev.depviz.log;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 日志文件的生命周期。
 *
 * <p>这些用例的存在理由都很具体：早先 {@code toFile} 在锁外建 writer，两个线程并发调用
 * 会漏掉一个 fd；无上限的文件级 {@code debug} 日志能一天写几十 MB。
 */
public class LogFileTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @After
    public void cleanup() {
        Log.resetForTest();
    }

    @Test
    public void writesLinesToFileWithTimestampAndLevel() throws Exception {
        File dir = tmp.newFolder("logs");
        File f = Log.toFile(dir, "depviz.log");
        assertNotNull("toFile 必须返回文件", f);

        Log.info("test", "hello");
        Log.error("test", "boom");

        String text = read(f);
        assertTrue("要有 INFO 行: " + text, text.contains("INFO  ["));
        assertTrue("要有 ERROR 行", text.contains("ERROR ["));
        assertTrue("要有 tag 与消息", text.contains("test - hello"));
        assertTrue("要有时间戳", text.matches("(?s).*\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}.*"));
    }

    @Test
    public void fileLevelFiltersDebug() throws Exception {
        File dir = tmp.newFolder("logs");
        Log.fileLevel(Log.Level.INFO);
        File f = Log.toFile(dir, "depviz.log");
        assertNotNull(f);

        Log.debug("test", "quiet");
        Log.info("test", "loud");

        String text = read(f);
        assertTrue("debug 应被文件级别挡住: " + text, !text.contains("quiet"));
        assertTrue("info 应写进去", text.contains("loud"));
    }

    @Test
    public void nestedPathCreatesParentDirectories() throws Exception {
        File deep = new File(tmp.getRoot(), "a/b/c/depviz.log");
        File f = Log.toFile(deep);
        assertNotNull("父目录要自动建出来", f);
        Log.info("test", "deep");
        assertTrue(read(f).contains("deep"));
    }

    @Test
    public void nullDirIsRejectedWithoutThrowing() {
        assertNull(Log.toFile((File) null));
        assertNull(Log.toFile(null, "x.log"));
        assertNull(Log.toFile(tmp.getRoot(), ""));
        assertNull(Log.toFile(tmp.getRoot(), null));
    }

    @Test
    public void reopenClosesThePreviousWriter() throws Exception {
        // 并发漏 fd 的回归用例：连开 200 次只应该留下最后一个 writer，
        // 且最后一个仍然写得到（= 前面那些确实被 close 了，没有把它们的内容丢掉）。
        File dir = tmp.newFolder("logs");
        File last = null;
        for (int i = 0; i < 200; i++) {
            last = Log.toFile(dir, "depviz.log");
        }
        assertNotNull(last);
        Log.warn("test", "after-many-reopens");
        assertTrue(read(last).contains("after-many-reopens"));
    }

    @Test
    public void rotatesWhenFileGrowsPastTheLimit() throws Exception {
        File dir = tmp.newFolder("logs");
        File f = Log.toFile(dir, "depviz.log");
        assertNotNull(f);
        // 控制台级别抬到 ERROR，否则这 9.6 MB 会全打到测试输出里
        Log.consoleLevel(Log.Level.ERROR);

        // 上限 8 MB。每条 8000 字节写 1200 条 = 9.6 MB，必然滚至少一次。
        StringBuilder pad = new StringBuilder();
        for (int i = 0; i < 800; i++) {
            pad.append("0123456789");
        }
        String line = pad.toString();
        for (int i = 0; i < 1200; i++) {
            Log.info("test", line);
        }

        File backup = new File(dir, "depviz.log.1");
        assertTrue("应该滚出一份 .1 备份", backup.isFile());
        assertTrue("当前文件滚过之后不该超过上限太多，实际 " + f.length(),
                f.length() < 9L * 1024 * 1024);
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
