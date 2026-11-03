package cn.dmego.dev.depviz.scan;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeFalse;

/** 包装器先退出、后代 JVM 在慢 shutdown hook 中的取消和超时回归。 */
public final class CancellerTest {
    @Rule public final TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void cancellationWaitsForSlowDescendantToExit() throws Exception {
        checkTreeStopped(false, false);
    }

    @Test
    public void timeoutStopsSlowDescendant() throws Exception {
        checkTreeStopped(true, false);
    }

    @Test
    public void interruptedRunnerStopsSlowDescendant() throws Exception {
        checkTreeStopped(false, true);
    }

    private void checkTreeStopped(boolean timeout, boolean interrupt) throws Exception {
        assumeFalse(System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win"));
        File root = tmp.newFolder();
        File script = new File(root, "mvn");
        String java = new File(System.getProperty("java.home"), "bin/java").getAbsolutePath();
        String classpath = System.getProperty("java.class.path");
        Files.write(script.toPath(), Arrays.asList("#!/bin/sh", "\"" + java + "\" -cp \""
                + classpath + "\" '" + SlowDescendant.class.getName() + "' \"$PWD\" &", "wait"),
                StandardCharsets.UTF_8);
        assertTrue(script.setExecutable(true));
        Canceller canceller = new Canceller();
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<ScanOutcome> result = new AtomicReference<ScanOutcome>();
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        ScanRequest req = new ScanRequest.Builder().projectDir(root)
                .outputFile(new File(root, "tree.txt"))
                .mavenExecutable(script.getAbsolutePath()).timeoutSeconds(timeout ? 3 : 20).build();
        Thread runner = new Thread(() -> {
            try {
                result.set(MvnRunner.run(req, line -> ready.countDown(), canceller));
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        runner.start();
        List<String> pids = null;
        try {
            assertTrue("后代 JVM 就绪", ready.await(10, TimeUnit.SECONDS));
            pids = Files.readAllLines(new File(root, "child.pid").toPath(), StandardCharsets.UTF_8);
            if (interrupt) {
                runner.interrupt();
            } else if (!timeout) {
                canceller.cancel();
            }
            runner.join(10000);
            assertFalse("runner 必须退出", runner.isAlive());
            assertNull(failure.get());
            assertNotNull(result.get());
            assertEquals(timeout || interrupt ? ScanOutcome.Status.FAILED : ScanOutcome.Status.CANCELLED,
                    result.get().status);
            if (interrupt) {
                assertTrue("清理后应保留中断标记", runner.isInterrupted());
            }
            assertTrue("TERM 必须触发 JVM shutdown hook", new File(root, "hook.started").isFile());
            Process probe = new ProcessBuilder("kill", "-0", pids.get(0)).start();
            assertTrue(probe.waitFor(1, TimeUnit.SECONDS));
            assertNotEquals("取消返回后后代必须已经消失", 0, probe.exitValue());
        } finally {
            canceller.cancel();
            if (pids != null) {
                Process cleanup = new ProcessBuilder("kill", "-KILL", pids.get(0)).start();
                cleanup.waitFor(1, TimeUnit.SECONDS);
            }
            runner.join(10000);
        }
    }

    public static final class SlowDescendant {
        public static void main(String[] args) throws Exception {
            File root = new File(args[0]);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Files.write(new File(root, "hook.started").toPath(), new byte[]{1});
                    Thread.sleep(30000);
                } catch (Exception ignored) {
                }
            }));
            Process pid = new ProcessBuilder("sh", "-c", "echo $PPID").start();
            byte[] bytes = new byte[64];
            int n = pid.getInputStream().read(bytes);
            pid.waitFor();
            Files.write(new File(root, "child.pid").toPath(), Arrays.asList(
                    new String(bytes, 0, n, StandardCharsets.UTF_8).trim()), StandardCharsets.UTF_8);
            System.out.println("READY");
            System.out.flush();
            Thread.sleep(600000);
        }
    }
}
