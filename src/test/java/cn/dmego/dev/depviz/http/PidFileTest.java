package cn.dmego.dev.depviz.http;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * pid 文件：{@code depviz.sh stop} 唯一能用的「停哪个进程」依据。
 *
 * <p>为什么由 Java 写、而不是脚本记 {@code $!}：Git Bash 里 {@code $!} 是 MSYS
 * 自己的编号，任务管理器和 {@code taskkill} 认的是另一套 Windows PID。脚本拿
 * MSYS 编号去 {@code taskkill}，等于停不掉原生 {@code java.exe} —— 这正是
 * Windows 上 {@code stop} 报「没有在运行」却停不掉的根因。进程自己写下的号，
 * 两边平台都认。
 */
public class PidFileTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void writesPlainDecimalPid() throws Exception {
        // 脚本用 read_pid 校验「纯数字」，任何空白、前缀、换行以外的修饰都会让它
        // 判定成坏文件 —— 那时 stop 会把一个活着的进程当成没在跑。
        HttpService.writePidFile(new File(tmp.getRoot(), "depviz.pid"), "12345");

        assertEquals("12345\n", content(new File(tmp.getRoot(), "depviz.pid")));
    }

    @Test
    public void createsMissingParentDirectories() throws Exception {
        // 首次启动、或者用户把 dist 复制走只留了 jar 与脚本时，logs/ 还不存在。
        File target = new File(new File(tmp.getRoot(), "logs"), "depviz.pid");
        assertFalse("前提：父目录一开始不存在", target.getParentFile().exists());

        HttpService.writePidFile(target, "42");

        assertTrue("父目录必须被创建", target.getParentFile().isDirectory());
        assertEquals("42\n", content(target));
    }

    @Test
    public void overwritesStalePidFromPreviousRun() throws Exception {
        // 上一轮的号必须先被盖掉：脚本若读到旧号，会去 taskkill 一个可能已经被
        // 系统复用给别的进程的 PID。
        File target = tmp.newFile("depviz.pid");
        Files.write(target.toPath(), "999\n".getBytes(StandardCharsets.UTF_8));

        HttpService.writePidFile(target, "12345");

        assertEquals("12345\n", content(target));
    }

    @Test
    public void writeFailureDoesNotAbortStartup() throws Exception {
        // 状态文件是辅助产物，写不了只能让 stop 退化，不该把已经绑好端口的服务拖死。
        File blocker = tmp.newFile("not-a-dir");

        HttpService.writePidFile(new File(blocker, "depviz.pid"), "1");

        assertTrue("写失败不应留下半截文件", !new File(blocker, "depviz.pid").exists());
    }

    @Test
    public void pidMatchesTheRunningJvm() throws Exception {
        // 内容必须是「系统认的号」，不是 MSYS 编号、也不是进程内部随便什么标识。
        // 用当前 JVM 自己的 pid 作为基准：它就是 RuntimeMXBean 里的那个数字。
        String jvmPid = java.lang.management.ManagementFactory.getRuntimeMXBean()
                .getName().split("@")[0];
        File target = new File(tmp.getRoot(), "depviz.pid");

        HttpService.writePidFile(target, jvmPid);

        assertEquals(jvmPid + "\n", content(target));
    }

    private static String content(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
