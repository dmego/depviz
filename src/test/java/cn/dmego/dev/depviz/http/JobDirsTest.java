package cn.dmego.dev.depviz.http;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** 任务使用锁必须在整个扫描期间保护目录，不能依赖某一个服务实例的寿命。 */
public class JobDirsTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void eachScanOwnsAUniqueDirectoryUntilFinished() throws Exception {
        File data = tmp.newFolder("unique");
        Set<File> dirs = new HashSet<File>();
        try {
            for (int i = 0; i < 20; i++) {
                File dir = JobDirs.newJobDir(data);
                assertNotNull(dir);
                assertTrue(dirs.add(dir));
                Files.write(new File(dir, "tree.txt").toPath(), new byte[]{1});
            }
            JobDirs.cleanStale(data);
            for (File dir : dirs) {
                assertTrue("启动清理不能删活跃任务", new File(dir, "tree.txt").isFile());
            }
        } finally {
            for (File dir : dirs) {
                JobDirs.finish(dir);
                assertFalse(dir.exists());
            }
        }
    }

    @Test
    public void startupRemovesOnlyAbandonedScanDirectories() throws Exception {
        File data = tmp.newFolder("stale");
        File stale = new File(data, "jobs/scan-abandoned/tree.txt");
        Files.createDirectories(stale.getParentFile().toPath());
        Files.write(stale.toPath(), new byte[]{1});
        File other = new File(data, "jobs/keep.txt");
        Files.write(other.toPath(), new byte[]{2});
        JobDirs.cleanStale(data);
        assertFalse(stale.getParentFile().exists());
        assertTrue(other.isFile());
    }

    @Test
    public void cleanupPreservesAnotherJvmUntilItExits() throws Exception {
        File data = tmp.newFolder("process");
        Process child = new ProcessBuilder(javaExecutable(), "-cp",
                System.getProperty("java.class.path"), Owner.class.getName(), data.getPath())
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        File dir = null;
        try {
            String path = new java.io.BufferedReader(new java.io.InputStreamReader(
                    child.getInputStream(), StandardCharsets.UTF_8)).readLine();
            assertNotNull("子 JVM 应成功认领目录", path);
            dir = new File(path);
            JobDirs.cleanStale(data);
            JobDirs.cleanStale(data);
            assertTrue("多次新实例清理仍保留活跃目录", new File(dir, "tree.txt").isFile());
            child.getOutputStream().close();
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue());
            JobDirs.cleanStale(data);
            assertFalse("任务进程退出后才能回收", dir.exists());
        } finally {
            child.destroyForcibly();
            child.waitFor(10, TimeUnit.SECONDS);
        }
    }

    @Test
    public void nullAndUnownedPathsCannotBeDeleted() throws Exception {
        assertNull(JobDirs.newJobDir(null));
        JobDirs.cleanStale(null);
        JobDirs.finish(null);
        File unrelated = tmp.newFolder("unowned");
        JobDirs.finish(unrelated);
        assertTrue(unrelated.exists());
    }

    private static String javaExecutable() {
        return new File(System.getProperty("java.home"), "bin/"
                + (System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java"))
                .getPath();
    }

    /** 测试进程故意不 finish，模拟服务退出而任务目录遗留。 */
    public static class Owner {
        public static void main(String[] args) throws Exception {
            File dir = JobDirs.newJobDir(new File(args[0]));
            if (dir == null) {
                throw new IllegalStateException("无法认领任务目录");
            }
            Files.write(new File(dir, "tree.txt").toPath(), new byte[]{1});
            System.out.println(dir.getPath());
            System.out.flush();
            while (System.in.read() != -1) {
                // 等父进程关闭 stdin。
            }
        }
    }
}
