package cn.dmego.dev.depviz.http;

import cn.dmego.dev.depviz.discover.ProjectInfo;
import cn.dmego.dev.depviz.model.Module;
import cn.dmego.dev.depviz.scan.ScanRequest;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 扫描日志落盘：{@code data/<项目名>/maven.log} 与保留下来的 {@code tree.txt}。
 *
 * <p>为什么值得测：这套东西存在的唯一理由是「失败之后还能查」，而它最容易坏在
 * 无人注意的地方 —— 目录认领白名单漏了文件名，第二次扫描就静默不再写日志；
 * 临时目录删除早于复制，失败现场就一起没了。两者都不会有任何报错。
 */
public class ScanLogTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    // ---------------------------------------------------------- ScanLog 本身

    /** 每一行都落到磁盘，且能在进程结束后被读到。 */
    @Test
    public void writesEveryLine() throws Exception {
        File f = new File(tmp.newFolder("logs"), "maven.log");
        ScanLog log = ScanLog.open(f);
        assertNotNull(log);
        log.line("[INFO] Scanning for projects...");
        log.line("[ERROR] Failed to execute goal");
        log.close();

        String text = read(f);
        assertTrue(text, text.startsWith("[INFO] Scanning for projects...\n"));
        assertTrue(text, text.endsWith("[ERROR] Failed to execute goal\n"));
    }

    /** 自己的说明行带 {@code === } 前缀，否则和 Maven 输出混在一起读不出来。 */
    @Test
    public void notesArePrefixed() throws Exception {
        File f = new File(tmp.newFolder("logs"), "maven.log");
        ScanLog log = ScanLog.open(f);
        log.note("项目   /code/demo");
        log.line("[INFO] hello");
        log.close();

        String[] lines = read(f).split("\n");
        assertEquals("=== 项目   /code/demo", lines[0]);
        assertEquals("[INFO] hello", lines[1]);
    }

    /** 重新扫描覆盖上一次的日志 —— 用户关心的是「最近一次发生了什么」。 */
    @Test
    public void reopensAsOverwrite() throws Exception {
        File dir = tmp.newFolder("logs");
        File f = new File(dir, "maven.log");
        ScanLog first = ScanLog.open(f);
        first.line("old run");
        first.close();

        ScanLog second = ScanLog.open(f);
        second.line("new run");
        second.close();

        assertEquals("new run\n", read(f));
    }

    /** 超过上限后停止写入，并在文件末尾说明为什么截断（而不是静默变短）。 */
    @Test
    public void truncatesAtTheLimit() throws Exception {
        File f = new File(tmp.newFolder("logs"), "maven.log");
        ScanLog log = ScanLog.open(f);
        StringBuilder pad = new StringBuilder(1024);
        for (int i = 0; i < 1024; i++) {
            pad.append('x');
        }
        String line = pad.toString();
        // 8 MB 上限：写 9000 行 1 KB 必定越过
        for (int i = 0; i < 9000; i++) {
            log.line(line);
        }
        log.line("这条不该出现");
        log.close();

        long size = f.length();
        assertTrue("日志应被截断在 8 MB 附近，实际 " + size, size < 9L * 1024 * 1024);
        String text = read(f);
        assertTrue(text, text.contains("后续输出已省略"));
        assertFalse(text, text.contains("这条不该出现"));
    }

    /** 目录建不出来时返回 {@code null}，绝不让扫描因此失败。 */
    @Test
    public void unwritableFileYieldsNull() throws Exception {
        // 拿一个普通文件当父目录，mkdirs 必然失败
        File notADir = tmp.newFile("not-a-dir");
        assertNull(ScanLog.open(new File(notADir, "maven.log")));
    }

    // ---------------------------------------------------------- ScanProgress

    /** 发现之后立刻建好日志并写下头部：命令、项目、模块数都要在。 */
    @Test
    public void progressWritesHeaderOnDiscover() throws Exception {
        File dataDir = tmp.newFolder("data");
        File project = tmp.newFolder("demo");
        Job job = new Job("job-1", null);
        ScanProgress progress = new ScanProgress(job, new GraphStore(dataDir), baseRequest(project));

        progress.project(info(project, project));

        assertNotNull("发现后应给出日志路径", job.logFile);
        String text = read(new File(job.logFile));
        assertTrue(text, text.contains("项目   " + project.getAbsolutePath()));
        assertTrue(text, text.contains("模块   1 个"));
        assertTrue(text, text.contains("maven-dependency-plugin:3.6.0:tree"));
        progress.finish("done", null, null);
    }

    /** 每一行同时进内存队列（前端轮询）和文件（事后查）。 */
    @Test
    public void progressWritesLinesBothWays() throws Exception {
        File dataDir = tmp.newFolder("data");
        File project = tmp.newFolder("demo");
        Job job = new Job("job-2", null);
        ScanProgress progress = new ScanProgress(job, new GraphStore(dataDir), baseRequest(project));

        progress.project(info(project, project));
        progress.log("[INFO] downloading");
        progress.finish("done", null, null);

        assertEquals(Collections.singletonList("[INFO] downloading"), new ArrayList<String>(job.log));
        String text = read(new File(job.logFile));
        assertTrue(text, text.contains("[INFO] downloading"));
        assertTrue("完成时应有结论行", text.contains("扫描完成，Maven 输出共 1 行"));
    }

    /** 失败时把依赖树复制到项目目录 —— 解析报错说的「第 117 行」就在里面。 */
    @Test
    public void failedScanKeepsTheTree() throws Exception {
        File dataDir = tmp.newFolder("data");
        File project = tmp.newFolder("demo");
        File outDir = tmp.newFolder("job-tmp");
        File tree = new File(outDir, "tree.txt");
        Files.write(tree.toPath(), "[INFO] root\n".getBytes(StandardCharsets.UTF_8));

        Job job = new Job("job-3", null);
        ScanProgress progress = new ScanProgress(job, new GraphStore(dataDir), baseRequest(project));
        progress.project(info(project, project));
        progress.finish("failed", "解析依赖树失败: 第 117 行无法解析", tree);

        File kept = new File(new File(dataDir, "demo"), "tree.txt");
        assertTrue("失败也要留下依赖树: " + kept, kept.isFile());
        assertEquals("[INFO] root\n", read(kept));
        String text = read(new File(job.logFile));
        assertTrue(text, text.contains("扫描失败: 解析依赖树失败: 第 117 行无法解析"));
        assertTrue(text, text.contains("依赖树已保留: " + kept.getAbsolutePath()));
    }

    /**
     * 第二次扫描仍能写进同一个项目目录。
     *
     * <p>这条守的是 {@link GraphStore#onlyOurs}：目录里已经有 {@code maven.log} 和
     * {@code tree.txt} 时，白名单漏掉它们就会把目录判成别人的，日志从此静默消失。
     */
    @Test
    public void secondScanStillWritesLog() throws Exception {
        File dataDir = tmp.newFolder("data");
        File project = tmp.newFolder("demo");
        File outDir = tmp.newFolder("job-tmp");
        File tree = new File(outDir, "tree.txt");
        Files.write(tree.toPath(), "root\n".getBytes(StandardCharsets.UTF_8));

        for (int i = 0; i < 2; i++) {
            Job job = new Job("job-" + i, null);
            ScanProgress progress =
                    new ScanProgress(job, new GraphStore(dataDir), baseRequest(project));
            progress.project(info(project, project));
            progress.log("round " + i);
            progress.finish("done", null, tree);
            assertNotNull("第 " + (i + 1) + " 次扫描应仍有日志路径", job.logFile);
            assertEquals(new File(new File(dataDir, "demo"), "maven.log").getAbsolutePath(),
                    job.logFile);
        }
        assertEquals("round 1", lastLineBesidesNotes(new File(dataDir, "demo/maven.log")));
    }

    // ---------------------------------------------------------- 辅助

    private static ScanRequest baseRequest(File project) {
        return new ScanRequest.Builder()
                .projectDir(project)
                .outputFile(new File(project, "tree.txt"))
                .build();
    }

    private static ProjectInfo info(File selected, File root) {
        return new ProjectInfo(selected, root, new File(root, "pom.xml"), false, 3L,
                Collections.singletonList(new Module("com.example", "demo", "1", "jar", ".",
                        "pom.xml", 0, 0, null)));
    }

    private static String lastLineBesidesNotes(File f) throws Exception {
        String[] lines = read(f).split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].startsWith("===") && !lines[i].isEmpty()) {
                return lines[i];
            }
        }
        return "";
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
