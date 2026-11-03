package cn.dmego.dev.depviz.scan;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 运行 Maven 依赖树命令并收集日志、超时和结果状态。 */
public final class MvnRunner {

    /** 日志回调。实现方负责缓冲与增量下发。 */
    public interface LogSink {
        void line(String text);
    }

    private MvnRunner() {
    }

    public static ScanOutcome run(ScanRequest req, LogSink sink, Canceller canceller) {
        File outFile = req.outputFile;
        // 插件会追加输出，先删除旧文件以免混入上次结果。
        if (outFile.exists() && !outFile.delete()) {
            return ScanOutcome.failed("无法清理旧的输出文件: " + outFile);
        }
        File parent = outFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return ScanOutcome.failed("无法创建输出目录: " + parent);
        }

        List<String> cmd = MvnCommand.build(req);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(req.projectDir);
        pb.redirectErrorStream(true);
        applyJavaHome(pb, req.javaHome);
        applyMemoryCap(pb, req.xmx);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return ScanOutcome.failed("启动 Maven 失败: " + e.getMessage());
        }
        if (canceller != null) {
            canceller.attach(process);
        }

        Drainer drainer = new Drainer(process, sink);
        Thread t = new Thread(drainer, "mvn-log");
        t.setDaemon(true);
        t.start();

        String waitError = awaitExit(process, req.timeoutSeconds, t);
        if (waitError != null) {
            destroy(process);
            return ScanOutcome.failed(waitError);
        }
        drainer.flush();

        if (canceller != null && canceller.wasCancelled()) {
            return ScanOutcome.cancelled();
        }
        return verifyOutput(process.exitValue(), outFile, drainer);
    }

    /** 等待 Maven 和日志线程结束；返回失败原因或 {@code null}。 */
    private static String awaitExit(Process process, int timeoutSeconds, Thread logThread) {
        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "扫描被中断";
        }
        if (!finished) {
            return "Maven 超过 " + timeoutSeconds + " 秒未结束，已终止";
        }
        try {
            logThread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    /** 验证退出码和依赖树文件；依赖完整性由后续解析检查。 */
    private static ScanOutcome verifyOutput(int code, File outFile, Drainer drainer) {
        if (code != 0) {
            return ScanOutcome.failed("Maven 退出码 " + code + "\n" + drainer.tail());
        }
        if (!outFile.isFile() || outFile.length() == 0) {
            return ScanOutcome.failed("Maven 未产出依赖树文件（退出码 0 但文件为空）\n" + drainer.tail());
        }
        return ScanOutcome.ok(outFile, drainer);
    }

    /** 仅在用户未设置 {@code -Xmx} 时追加内存限制，保留其他 {@code MAVEN_OPTS}。 */
    static void applyMemoryCap(ProcessBuilder pb, String xmx) {
        if (xmx == null || xmx.isEmpty()) {
            return;
        }
        String flag = xmx.startsWith("-Xmx") ? xmx : "-Xmx" + xmx;
        String existing = pb.environment().get("MAVEN_OPTS");
        if (existing == null || existing.trim().isEmpty()) {
            pb.environment().put("MAVEN_OPTS", flag + " -Dfile.encoding=UTF-8");
            return;
        }
        if (existing.contains("-Xmx")) {
            return;
        }
        pb.environment().put("MAVEN_OPTS", existing + " " + flag);
    }

    private static void applyJavaHome(ProcessBuilder pb, String javaHome) {
        if (javaHome == null || javaHome.isEmpty()) {
            return;
        }
        pb.environment().put("JAVA_HOME", javaHome);
        if (!pb.environment().containsKey("MAVEN_OPTS")) {
            pb.environment().put("MAVEN_OPTS", "-Dfile.encoding=UTF-8");
        }
    }

    /** 超时后终止 Maven 进程及其子进程。 */
    private static void destroy(Process p) {
        Canceller.killTree(p);
    }

    /** 后台逐行读取并记录 Maven 输出。 */
    static final class Drainer implements Runnable {
        private static final int KEEP_TAIL = 200;

        private final Process process;
        private final LogSink sink;
        private final StringBuilder tail = new StringBuilder();
        private int lineCount;
        private int missingCount;

        Drainer(Process process, LogSink sink) {
            this.process = process;
            this.sink = sink;
        }

        /** 只匹配 Maven 缺少依赖 POM 时使用的专用短语。 */
        static boolean isMissingDependency(String line) {
            return line.contains("no dependency information available");
        }

        @Override
        public void run() {
            try {
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = r.readLine()) != null) {
                    record(line);
                    if (sink != null) {
                        sink.line(line);
                    }
                }
            } catch (IOException ignored) {
                // 进程退出时输出流可能关闭。
            }
        }

        synchronized void record(String line) {
            lineCount++;
            if (isMissingDependency(line)) {
                missingCount++;
            }
            tail.append(line).append('\n');
            if (tail.length() > 64 * 1024) {
                tail.delete(0, tail.length() - 48 * 1024);
            }
        }

        synchronized void flush() {
            // 当前按行下发，无需缓冲。
        }

        synchronized int lines() {
            return lineCount;
        }

        /** Maven 输出中缺少依赖 POM 的行数。 */
        synchronized int missing() {
            return missingCount;
        }

        synchronized String tail() {
            String[] all = tail.toString().split("\n");
            int from = Math.max(0, all.length - KEEP_TAIL);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < all.length; i++) {
                sb.append(all[i]).append('\n');
            }
            return sb.toString();
        }
    }

}
