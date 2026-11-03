package cn.dmego.dev.depviz.scan;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** 一次扫描的取消开关；取消结束时等待整棵进程树退出。 */
public final class Canceller {
    private Process process;
    private boolean cancelled;
    private static final long GRACE_MS = 500;
    private static final long KILL_WAIT_MS = 2000;

    public synchronized void attach(Process p) {
        process = p;
        if (cancelled) {
            killTree(p);
        }
    }

    public synchronized void cancel() {
        cancelled = true;
        if (process != null) {
            killTree(process);
        }
    }

    // 与 cancel 共用锁：runner 不能在后代清理尚未完成时返回 CANCELLED。
    public synchronized boolean wasCancelled() {
        return cancelled;
    }

    static void killTree(Process process) {
        // 中断本身也是终止扫描的原因；清理不能因已有中断标记而跳过等待。
        boolean interrupted = Thread.interrupted();
        try {
            if (isWindows()) {
                killTreeOnWindows(process);
                return;
            }
            List<Long> descendants = descendantsOf(process);
            signal(descendants, "TERM");
            process.destroy();
            awaitTree(process, descendants, GRACE_MS);
            signal(descendants, "KILL");
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            awaitTree(process, descendants, KILL_WAIT_MS);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void awaitTree(Process process, List<Long> descendants, long timeoutMillis) {
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try {
            while (System.nanoTime() < deadline) {
                boolean running = process.isAlive();
                for (Long pid : descendants) {
                    running |= alive(pid.longValue());
                }
                if (!running) {
                    return;
                }
                try {
                    Thread.sleep(25);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void signal(List<Long> pids, String signal) {
        for (Long pid : pids) {
            if (alive(pid.longValue())) {
                command("kill", "-" + signal, pid.toString());
            }
        }
    }

    private static boolean alive(long pid) {
        return command("kill", "-0", String.valueOf(pid)) == 0;
    }

    /** 系统辅助命令也有界等待并收尾，不留下探测进程。 */
    private static int command(String... args) {
        Process command = null;
        try {
            command = new ProcessBuilder(args).redirectErrorStream(true).start();
            if (waitFor(command, GRACE_MS)) {
                return command.exitValue();
            }
        } catch (java.io.IOException ignored) {
            // 不支持系统命令时由 Process.destroyForcibly 兜底根进程。
        } finally {
            stopProbe(command);
        }
        return -1;
    }

    private static void killTreeOnWindows(Process process) {
        long pid = pidOf(process);
        if (pid > 0) {
            command("taskkill", "/T", "/F", "/PID", String.valueOf(pid));
        }
        if (!waitFor(process, GRACE_MS)) {
            process.destroyForcibly();
            waitFor(process, KILL_WAIT_MS);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** 先子后父记录 PID，包装脚本退出后仍能终止已经被重新托管的后代。 */
    private static List<Long> descendantsOf(Process process) {
        List<Long> out = new ArrayList<Long>();
        long pid = pidOf(process);
        if (pid > 0) {
            collect(pid, out, new HashSet<Long>(), 0);
        }
        return out;
    }

    private static void collect(long pid, List<Long> out, Set<Long> seen, int depth) {
        if (depth > 32 || !seen.add(Long.valueOf(pid))) {
            return;
        }
        Process probe = null;
        try {
            probe = new ProcessBuilder("pgrep", "-P", String.valueOf(pid))
                    .redirectErrorStream(true).start();
            if (!waitFor(probe, GRACE_MS)) {
                return;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    probe.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        long child = Long.parseLong(line.trim());
                        collect(child, out, seen, depth + 1);
                        out.add(Long.valueOf(child));
                    } catch (NumberFormatException ignored) {
                        // pgrep 的错误文本不作为 PID。
                    }
                }
            }
        } catch (java.io.IOException ignored) {
            // 没有 pgrep 时仍可终止根进程。
        } finally {
            stopProbe(probe);
        }
    }

    /** 清理不响应中断，但保留标记给调用方；中断不能留下扫描后代或辅助命令。 */
    private static boolean waitFor(Process process, long timeoutMillis) {
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try {
            while (process.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                try {
                    return process.waitFor(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            return true;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void stopProbe(Process probe) {
        if (probe != null && probe.isAlive()) {
            probe.destroyForcibly();
            waitFor(probe, GRACE_MS);
        }
    }

    private static long pidOf(Process process) {
        try {
            // 从公开 Process 类取方法，避免 JDK 9+ 的模块访问限制；仍可编译到 Java 8。
            Object pid = Process.class.getMethod("pid").invoke(process);
            return ((Number) pid).longValue();
        } catch (Exception ignored) {
            try {
                java.lang.reflect.Field field = process.getClass().getDeclaredField("pid");
                field.setAccessible(true);
                return ((Number) field.get(process)).longValue();
            } catch (Exception unavailable) {
                return -1;
            }
        }
    }
}
