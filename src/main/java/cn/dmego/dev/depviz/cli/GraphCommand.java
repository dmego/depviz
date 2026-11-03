package cn.dmego.dev.depviz.cli;

import cn.dmego.dev.depviz.discover.DiscoverException;
import cn.dmego.dev.depviz.discover.ProjectInfo;
import cn.dmego.dev.depviz.json.GraphJsonWriter;
import cn.dmego.dev.depviz.model.Module;
import cn.dmego.dev.depviz.scan.ScanPipeline;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** 命令行扫描、已有依赖树转图，以及图统计入口。 */
public final class GraphCommand {

    private GraphCommand() {
    }

    /**
     * @param dir      项目目录
     * @param treeFile 已有的依赖树文件（跳过跑 Maven）；为 {@code null} 时现场扫描
     * @param outFile  写出的 JSON
     */
    public static int run(File dir, File treeFile, File outFile) {
        return run(Options.of(dir, treeFile, outFile));
    }

    /** 命令行选项。四个位置参数里两个是 {@code File} 一个是 {@code boolean}，传反了编译期查不出来。 */
    public static final class Options {
        public final File dir;
        public final File treeFile;
        public final File outFile;
        public final boolean statsOnly;

        private Options(File dir, File treeFile, File outFile, boolean statsOnly) {
            this.dir = dir;
            this.treeFile = treeFile;
            this.outFile = outFile;
            this.statsOnly = statsOnly;
        }

        public static Options of(File dir, File treeFile, File outFile) {
            return new Options(dir, treeFile, outFile, false);
        }

        /** {@code --stats}：只打印统计不写文件，所以 {@code outFile} 无意义（传 null）。 */
        public static Options stats(File dir, File treeFile) {
            return new Options(dir, treeFile, null, true);
        }
    }

    /**
     * @param opts 见 {@link Options}
     */
    public static int run(Options opts) {
        File dir = opts.dir;
        File treeFile = opts.treeFile;
        File outFile = opts.outFile;
        boolean statsOnly = opts.statsOnly;
        try {
            ScanPipeline pipeline = new ScanPipeline();
            cn.dmego.dev.depviz.scan.ScanRequest request = baseRequest();
            ProjectInfo info;
            cn.dmego.dev.depviz.model.Graph graph;
            if (treeFile != null) {
                info = pipeline.discover(dir, request.profiles);
                graph = parseExisting(treeFile);
            } else {
                // 与 scan 子命令走同一条路径：失败时把 Maven 的真实报错打出来。
                // 早先这里只打一句「扫描失败」，返回码还被当成 0 ——
                // 用户看不到原因，脚本里看起来却是成功的。
                ScanPipeline.ScanResult r = pipeline.scan(dir, tempDir(), request, null, null);
                if (!r.ok) {
                    System.err.println("扫描失败: " + r.message);
                    return 2;
                }
                graph = r.graph;
                info = r.info;
            }
            if (graph.moduleCount() != info.moduleCount()) {
                System.err.println("警告: 树里 " + graph.moduleCount() + " 个根，发现阶段 " + info.moduleCount()
                        + " 个模块 —— 未发现的模块保留独立元数据槽位");
            }
            if (statsOnly) {
                printStats(graph);
                return 0;
            }
            String json = toJson(graph, info);
            Files.write(outFile.toPath(), json.getBytes(StandardCharsets.UTF_8));
            System.out.println("wrote " + outFile + " (" + json.length() + " chars, "
                    + graph.size() + " occ, " + graph.artifacts().size() + " ga, "
                    + graph.moduleCount() + " modules)");
            return 0;
        } catch (DiscoverException e) {
            System.err.println("发现失败: " + e.getMessage());
            return 2;
        } catch (IOException e) {
            System.err.println("读写失败: " + e.getMessage());
            return 2;
        }
    }

    /**
     * 跑完整流程：Maven 扫描 + 解析 + 写 JSON。
     *
     * @param offline {@code null} 用默认（离线）；{@code FALSE} 强制联网
     */
    public static int scan(File dir, File outFile, Boolean offline) {
        try {
            ScanPipeline pipeline = new ScanPipeline();
            File outDir = tempDir();
            cn.dmego.dev.depviz.scan.ScanRequest req = baseRequest()
                    .newBuilder()
                    .offline(offline == null || offline.booleanValue())
                    .build();
            ScanPipeline.ScanResult r = pipeline.scan(dir, outDir, req, null, null);
            if (!r.ok) {
                System.err.println("扫描失败: " + r.message);
                return 2;
            }
            String json = toJson(r.graph, r.info);
            Files.write(outFile.toPath(), json.getBytes(StandardCharsets.UTF_8));
            System.out.println("wrote " + outFile + " (" + json.length() + " chars, "
                    + r.graph.size() + " occ, " + r.graph.artifacts().size() + " ga, "
                    + r.graph.moduleCount() + " modules, " + (r.millis / 1000.0) + "s)");
            return 0;
        } catch (DiscoverException e) {
            System.err.println("发现失败: " + e.getMessage());
            return 2;
        } catch (IOException e) {
            System.err.println("读写失败: " + e.getMessage());
            return 2;
        }
    }

    /**
     * {@code graph <graph.json> --stats}：对已有产物出统计。
     *
     * <p>排查时手上通常只有上次扫出来的 json，没有原始 tree.txt，也没有项目目录。
     * 直接数 occ 数组就够了 —— 不需要反序列化成 {@code Graph}。
     */
    public static int statsOfJson(File jsonFile) {
        Object doc;
        try {
            doc = cn.dmego.dev.depviz.config.JsonIn.parse(
                    new String(java.nio.file.Files.readAllBytes(jsonFile.toPath()),
                            StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("读取失败: " + e.getMessage());
            return 2;
        }
        if (!(doc instanceof java.util.Map)) {
            System.err.println("不是合法的图 JSON: " + jsonFile);
            return 2;
        }
        java.util.Map<?, ?> root = (java.util.Map<?, ?>) doc;
        java.util.List<?> occ = asList(root.get("occ"));
        java.util.List<?> gas = asList(root.get("gas"));
        java.util.List<?> modules = asList(root.get("modules"));
        int roots = 0;
        int effective = 0;
        int conflict = 0;
        int duplicate = 0;
        for (Object item : occ) {
            if (!(item instanceof java.util.Map)) {
                continue;
            }
            java.util.Map<?, ?> o = (java.util.Map<?, ?>) item;
            if (num(o.get("p")) < 0) {
                roots++;
            }
            if (num(o.get("e")) != 0) {
                effective++;
            }
            Object reason = o.get("o");
            if ("conflict".equals(reason)) {
                conflict++;
            } else if ("duplicate".equals(reason)) {
                duplicate++;
            }
        }
        System.out.println("occ        " + occ.size());
        System.out.println("roots      " + roots);
        System.out.println("ga         " + gas.size());
        System.out.println("modules    " + modules.size());
        System.out.println("effective  " + effective + " (non-root " + (effective - roots) + ")");
        System.out.println("conflict   " + conflict);
        System.out.println("duplicate  " + duplicate);
        return 0;
    }

    private static java.util.List<?> asList(Object v) {
        return v instanceof java.util.List ? (java.util.List<?>) v : java.util.Collections.emptyList();
    }

    private static long num(Object v) {
        return v instanceof Number ? ((Number) v).longValue() : -1L;
    }

    /** {@code graph --stats}：只打印，不落盘。 */
    private static void printStats(cn.dmego.dev.depviz.model.Graph graph) {
        int roots = graph.moduleRoots().size();
        int effective = 0;
        int conflict = 0;
        int duplicate = 0;
        for (cn.dmego.dev.depviz.model.Occurrence o : graph.occurrences()) {
            if (o.effective) {
                effective++;
            }
            if ("conflict".equals(o.omitReason)) {
                conflict++;
            } else if ("duplicate".equals(o.omitReason)) {
                duplicate++;
            }
        }
        System.out.println("occ        " + graph.size());
        System.out.println("roots      " + roots);
        System.out.println("ga         " + graph.artifacts().size());
        System.out.println("effective  " + effective + " (non-root " + (effective - roots) + ")");
        System.out.println("conflict   " + conflict);
        System.out.println("duplicate  " + duplicate);
        System.out.println("maxDepth   " + graph.maxDepth());
    }

    /** 只接已有的树文件时，project 元数据里能填的只有模块数。 */
    public static String toJson(cn.dmego.dev.depviz.model.Graph graph, ProjectInfo info) {
        GraphJsonWriter.ProjectMeta meta = new GraphJsonWriter.ProjectMeta();
        meta.id = info.rootDir;
        meta.name = new File(info.rootDir).getName();
        meta.rootDir = info.rootDir;
        meta.declaredDependencyCount = info.declaredDependencyCount();
        meta.seconds = 0;
        List<Module> modules = info.modules;
        return GraphJsonWriter.write(graph, meta, modules);
    }

    private static cn.dmego.dev.depviz.model.Graph parseExisting(File treeFile) throws IOException {
        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                new java.io.FileInputStream(treeFile), StandardCharsets.UTF_8));
        try {
            return cn.dmego.dev.depviz.parse.TreeParser.parse(r);
        } finally {
            r.close();
        }
    }

    /**
     * 命令行扫描的基准请求。
     *
     * <p><b>必须与 HTTP 路径用同一份配置</b>（{@code $DEPVIZ_HOME/config.json} 或
     * {@code ~/.depviz/config.json}）：早先这里只设了 {@code projectDir} 与
     * {@code outputFile}，于是 {@code depviz scan} 永远走离线、永远用 PATH 里的
     * {@code mvn}、永远不注入 {@code -Xmx}。用户明明在设置里配了 Maven 路径与
     * 本地仓库，命令行却完全无视 —— 扫 seata 这种必须联网的项目就只剩一句
     * 「扫描失败」，既没有原因也没有线索。
     */
    private static cn.dmego.dev.depviz.scan.ScanRequest baseRequest() {
        cn.dmego.dev.depviz.config.ConfigStore cfg = cn.dmego.dev.depviz.config.ConfigStore.defaultLocation().load();
        cn.dmego.dev.depviz.scan.ScanRequest.Builder b = new cn.dmego.dev.depviz.scan.ScanRequest.Builder()
                .projectDir(new File("."))
                .outputFile(new File("tree.txt"))
                .offline(cfg.offline())
                .xmx(cfg.resolvedXmx())
                .threads(cfg.threads());
        if (!cfg.maven().isEmpty()) {
            b.mavenExecutable(cfg.maven());
        }
        if (!cfg.javaHome().isEmpty()) {
            b.javaHome(cfg.javaHome());
        }
        if (!cfg.settingsXml().isEmpty()) {
            b.settingsXml(cfg.settingsXml());
        }
        if (!cfg.localRepo().isEmpty()) {
            b.localRepo(cfg.localRepo());
        }
        for (String p : cfg.profileList()) {
            b.profile(p);
        }
        return b.build();
    }

    private static File tempDir() throws IOException {
        File d = Files.createTempDirectory("depviz-cli").toFile();
        d.deleteOnExit();
        return d;
    }
}
