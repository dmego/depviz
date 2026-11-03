package cn.dmego.dev.depviz.scan;

import cn.dmego.dev.depviz.discover.Discoverer;
import cn.dmego.dev.depviz.discover.DiscoverException;
import cn.dmego.dev.depviz.discover.ProjectInfo;
import cn.dmego.dev.depviz.model.Graph;
import cn.dmego.dev.depviz.parse.TreeParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 一次完整扫描：发现 → 跑 Maven → 解析 → 出 JSON。
 *
 * <p>分两段是有意的：发现只读 XML，实测 3 ms；扫描要跑 Maven，实测秒级到分钟级。
 * 用户点目录后立刻能看到「61 个模块」的确认页，不用干等。
 */
public final class ScanPipeline {

    /**
     * 进度阶段，与前端 scan 页的文案一一对应。
     *
     * <p>只有这四段，因为只有这四段是<b>可观测</b>的：
     * <ul>
     *   <li>树是 {@code RESOLVE} 里 Maven 写出来的，写完立刻就是 {@code PARSE}，
     *       中间没有独立的时间段，所以没有单独的「出树」阶段。</li>
     *   <li>下载只在联网且本地仓库缺依赖时才有意义；靠日志里有没有
     *       {@code Downloading from} 去推断阶段既不可靠也不值得 ——
     *       用户在 {@code RESOLVE} 里看日志就够了。</li>
     * </ul>
     */
    public enum Stage {
        DISCOVER, RESOLVE, PARSE, WRITE
    }

    /** 进度回调。{@code progress} 是 0..1，未知时为 -1。 */
    public interface Progress {
        void stage(Stage stage);

        void log(String line);

        /**
         * 发现完成、Maven 还没开跑时回调一次。
         *
         * <p>存在的理由：调用方要在<b>看到 Maven 第一行输出之前</b>就知道项目根目录，
         * 才能把这次扫描的日志落到 {@code data/<项目名>/} 里 —— 等扫描结束再回忆
         * 该写哪儿，失败的那次已经没日志了。默认空实现，命令行（{@code progress == null}）
         * 不需要关心落盘。
         */
        default void project(ProjectInfo info) {
        }
    }

    private final Discoverer discoverer = new Discoverer();

    /** 只做发现（打开目录时立刻可用）。 */
    public ProjectInfo discover(File dir) throws DiscoverException {
        return discoverer.discover(dir);
    }

    public ProjectInfo discover(File dir, java.util.List<String> profiles) throws DiscoverException {
        return discoverer.discover(dir, profiles);
    }

    /**
     * 全量扫描。
     *
     * @param outDir 本次任务的独立目录（每次必须新建，因为 appendOutput 是追加）
     */
    public ScanResult scan(File dir, File outDir, ScanRequest base, Progress progress,
                           Canceller canceller) throws DiscoverException {
        long t0 = System.currentTimeMillis();
        emit(progress, Stage.DISCOVER);
        ProjectInfo info = discoverer.discover(dir, base.profiles);
        // 项目根确定之后、Maven 开跑之前通知一次：调用方要靠它决定日志写哪儿。
        // 放在 discover 之后是因为 rootDir 可能上移（在子目录点开的多模块项目），
        // 用用户选的那个目录命名会在 data/ 里留下一堆错误名字的目录。
        if (progress != null) {
            progress.project(info);
        }

        emit(progress, Stage.RESOLVE);
        File treeFile = new File(outDir, "tree.txt");
        ScanRequest req = base.newBuilder()
                .projectDir(new File(info.rootDir))
                .outputFile(treeFile)
                .build();
        // 「实际跑的请求」，不是用户点的那个。离线失败后会自动联网重试一次，
        // 之后所有对外字段（元数据条的「离线/在线」、命令回显里的 -o）都必须按它算，
        // 否则界面会一边说「在线」一边显示一条带 -o 的命令。
        ScanRequest effective = req;
        ScanOutcome outcome = MvnRunner.run(req, line -> {
            if (progress != null) {
                progress.log(line);
            }
        }, canceller);
        if (outcome.status == ScanOutcome.Status.CANCELLED) {
            return ScanResult.cancelled();
        }
        if (!outcome.isOk() && req.offline && looksLikeOfflineFailure(outcome.message)) {
            // 离线跑不动、而且原因确实是「本地仓库没有」，就自动联网重试一次。
            //
            // 为什么不在界面层做：用户点了「开始分析」，看到的是一个失败横幅和一个
            // 「联网重试」按钮 —— 那他这一趟点的「开始分析」等于白点。实测 seata
            // 这类项目（BOM 用 import scope、parent 在 build/pom.xml、版本走 ${revision}）
            // 在干净的本地仓库上必然离线失败，而失败信息埋在几千行 Maven 日志里。
            //
            // 只在这条精确条件下重试：离线 + 失败原因可识别为「仓库里没有」。
            // 编译错误、POM 语法错、超时、取消都不会走到这里。
            ScanRequest online = req.newBuilder().offline(false).build();
            if (progress != null) {
                progress.log("离线模式下无法解析部分依赖，自动联网重试一次…");
            }
            outcome = MvnRunner.run(online, line -> {
                if (progress != null) {
                    progress.log(line);
                }
            }, canceller);
            if (outcome.status == ScanOutcome.Status.CANCELLED) {
                return ScanResult.cancelled();
            }
            if (!outcome.isOk()) {
                return ScanResult.failed(outcome.message);
            }
            effective = online;
        } else if (!outcome.isOk()) {
            return ScanResult.failed(outcome.message);
        }

        emit(progress, Stage.PARSE);
        Graph graph;
        try {
            graph = parseTree(treeFile);
        } catch (IOException e) {
            return ScanResult.failed("读取依赖树失败: " + e.getMessage());
        } catch (RuntimeException e) {
            return ScanResult.failed("解析依赖树失败: " + e.getMessage());
        }

        emit(progress, Stage.WRITE);
        long ms = System.currentTimeMillis() - t0;
        return ScanResult.ok(info, graph, treeFile, ms, outcome.logLines, outcome.missingCount,
                effective);
    }

    /**
     * 这段失败信息是不是「离线模式下仓库里没有」。
     *
     * <p>只认 Maven 自己那几句固定文案（3.3.9 与 3.9.x 措辞略有不同，几种都收），
     * 不做模糊匹配 —— 误判的代价是凭空多跑一次联网扫描，会让人以为程序在偷偷下载。
     */
    static boolean looksLikeOfflineFailure(String message) {
        if (message == null) {
            return false;
        }
        String m = message;
        return m.contains("in offline mode")
                || m.contains("Non-resolvable import POM")
                || m.contains("Non-resolvable parent POM")
                || m.contains("The following artifacts could not be resolved")
                || (m.contains("Cannot access") && m.contains("offline"));
    }

    private static Graph parseTree(File treeFile) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(treeFile), StandardCharsets.UTF_8));
        try {
            return TreeParser.parse(r);
        } finally {
            r.close();
        }
    }

    private static void emit(Progress p, Stage s) {
        if (p != null) {
            p.stage(s);
        }
    }

    /** 扫描结果。 */
    public static final class ScanResult {
        public final boolean ok;
        public final boolean cancelled;
        public final String message;
        public final ProjectInfo info;
        public final Graph graph;
        public final File treeFile;
        public final long millis;
        public final int logLines;
        /** Maven 输出里「缺 POM」的行数，>0 时元数据条要变黄。 */
        public final int missingCount;
        /**
         * 这次扫描**实际跑的**请求。
         *
         * <p>与调用方传进来的 {@code base} 不同：离线失败后会自动联网重试一次，
         * 成功后真实模式是「联网」，命令行里也没有 {@code -o}。元数据条、命令回显
         * 都必须依据这个对象，否则会一边说「在线」一边显示一条带 {@code -o} 的命令。
         *
         * <p>失败与取消时为 {@code null}。
         */
        public final ScanRequest request;

        private ScanResult(boolean ok, boolean cancelled, String message, ProjectInfo info,
                           Graph graph, File treeFile, long millis, int logLines,
                           int missingCount, ScanRequest request) {
            this.ok = ok;
            this.cancelled = cancelled;
            this.message = message;
            this.info = info;
            this.graph = graph;
            this.treeFile = treeFile;
            this.millis = millis;
            this.logLines = logLines;
            this.missingCount = missingCount;
            this.request = request;
        }

        /** 最终是不是联网跑的。 */
        public boolean online() {
            return request != null && !request.offline;
        }

        static ScanResult ok(ProjectInfo info, Graph g, File tree, long ms, int logLines,
                             int missing, ScanRequest effective) {
            return new ScanResult(true, false, null, info, g, tree, ms, logLines, missing,
                    effective);
        }

        static ScanResult failed(String msg) {
            return new ScanResult(false, false, msg, null, null, null, 0, 0, 0, null);
        }

        static ScanResult cancelled() {
            return new ScanResult(false, true, "扫描已取消", null, null, null, 0, 0, 0, null);
        }
    }
}
