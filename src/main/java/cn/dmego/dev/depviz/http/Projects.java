package cn.dmego.dev.depviz.http;

import cn.dmego.dev.depviz.discover.ProjectInfo;
import cn.dmego.dev.depviz.json.GraphJsonWriter;
import cn.dmego.dev.depviz.log.Log;
import cn.dmego.dev.depviz.model.Graph;
import cn.dmego.dev.depviz.scan.MvnCommand;
import cn.dmego.dev.depviz.scan.ScanPipeline;
import cn.dmego.dev.depviz.scan.ScanRequest;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 项目表 + 图缓存 + 扫描作业调度。
 *
 * <p>扫完一次后 {@code /api/graph} 直接返回缓存好的 gzip 字节，不再重新序列化 ——
 * 序列化 2 MB 只要几十毫秒，但重跑 Maven 要几十秒。
 */
public final class Projects {

    private final Map<String, ProjectState> byKey = new ConcurrentHashMap<String, ProjectState>();
    /** 固定的存储位置（单元测试用）。为 {@code null} 时按 {@link #config} 现算。 */
    private final ProjectStore store;
    /** 固定的图目录（单元测试用）。为 {@code null} 时按 {@link #config} 现算。 */
    private final GraphStore graphStore;
    /**
     * 配置的活引用。
     *
     * <p>不能只在构造时读一次 {@code dataDir}：设置面板里改完数据目录，保存会成功，
     * 但服务里所有路径仍是启动时那个 —— 用户以为改了，产物照旧写在老地方，直到重启
     * 才发现。所以两个 Store 都按需从当前配置现算（见 {@link #store()} / {@link #graphStore()}）。
     */
    private final cn.dmego.dev.depviz.config.ConfigStore config;

    /** 作业表（含「同时只允许一个扫描」这条规则），职责独立，见 {@link JobRegistry}。 */
    private final JobRegistry jobs = new JobRegistry();

    /** @param store 为 {@code null} 时不做持久化（单元测试用） */
    public Projects(ProjectStore store) {
        this(store, null, null);
    }

    /**
     * @param store      项目索引（可为 {@code null}，单元测试不落盘）
     * @param graphStore 扫描产物目录（可为 {@code null}，此时图只在内存）
     */
    public Projects(ProjectStore store, GraphStore graphStore) {
        this(store, graphStore, null);
    }

    /**
     * 生产构造：存储位置跟着配置走，改完设置立刻生效。
     *
     * <p>用静态工厂而不是重载构造：{@code new Projects(null)} 同时匹配
     * {@code Projects(ProjectStore)} 与 {@code Projects(ConfigStore)}，编译期就是歧义。
     *
     * @param config 配置的<b>活引用</b>（不能是快照，否则改设置不生效）
     */
    public static Projects forConfig(cn.dmego.dev.depviz.config.ConfigStore config) {
        return new Projects(null, null, config);
    }

    private Projects(ProjectStore store, GraphStore graphStore,
                     cn.dmego.dev.depviz.config.ConfigStore config) {
        this.store = store;
        this.graphStore = graphStore;
        this.config = config;
    }

    /** 当前生效的项目索引；配置没接进来时为 {@code null}（不落盘）。 */
    private ProjectStore store() {
        return store != null ? store : (config == null ? null : ProjectStore.forConfig(config));
    }

    /** 当前生效的图目录；配置没接进来时为 {@code null}（图只在内存）。 */
    private GraphStore graphStore() {
        return graphStore != null ? graphStore
                : (config == null ? null : new GraphStore(config.dataDirFile()));
    }

    /** 只给测试用：直接登记一个项目状态，跳过扫描流程。 */
    void putForTest(ProjectState p) {
        byKey.put(p.key, p);
    }

    /** 作业表的只读视图入口，路由层用它查状态/取消。 */
    public JobRegistry jobRegistry() {
        return jobs;
    }

    public ProjectState project(String key) {
        return byKey.get(key);
    }

    public List<ProjectState> all() {
        List<ProjectState> list = new ArrayList<ProjectState>(byKey.values());
        Collections.sort(list, (a, b) -> Long.compare(b.when, a.when));
        return list;
    }

    /** 打开项目并记住（不跑 Maven）。 */
    public synchronized ProjectState open(ProjectInfo info) {
        ProjectState previous = byKey.get(info.rootDir);
        ProjectState p = new ProjectState(info.rootDir, new File(info.rootDir).getName(),
                System.currentTimeMillis(), info.moduleCount(),
                previous == null ? null : previous.payload);
        byKey.put(p.key, p);
        ProjectStore current = store();
        if (current != null) {
            current.put(new ProjectStore.Record(p.key, p.name, p.moduleCount, p.when, p.key));
        }
        return p;
    }

    /**
     * 启动时把磁盘上的「最近打开」读回内存。
     *
     * <p>元数据（名字、模块数、打开时间）来自 {@code projects.json}；
     * 上次扫描产出的图从 {@code <dataDir>/<项目名>/graph.json} 读回 ——
     * 重启后点进老项目能直接看图，不必等几十秒重扫一遍 Maven。
     * 没有图的项目（从没扫成功过，或者手工删了 data/）落到确认页重扫。
     *
     * <p>这里**不碰项目目录**：启动要遍历所有记住的项目，目录可能在网盘上或已经删掉，
     * 光是读 POM 就能把启动拖住。图里已经带了模块数，够了。
     */
    public void restore() {
        ProjectStore s = store();
        if (s == null) {
            return;
        }
        for (ProjectStore.Record r : s.load()) {
            ProjectState p = new ProjectState(r.key, r.name, r.when, r.moduleCount, null);
            byKey.put(r.key, p);
            loadFromDisk(p);
        }
    }

    /** 扫描成功后把图与 JSON 一起缓存。 */
    public void storeGraph(ProjectState p, Graph g, ProjectInfo info,
                           GraphJsonWriter.ProjectMeta meta) {
        // 顺手压一份：2.0 MB 的 JSON 现压要 20 ms CPU，而这张图在下次扫描前不变。
        // 压失败就把 gzip 留空，请求时回退到现压（不能因为省 CPU 而让接口 500）。
        byte[] bytes;
        byte[] gz;
        String json = GraphJsonWriter.write(g, meta, info.modules);
        String[] rels = GraphJsonWriter.relPathsOf(g, info.modules);
        String[] poms = GraphJsonWriter.pomPathsOf(g, info.modules);
        bytes = utf8(json);
        gz = bytes == null ? null : Gzip.compressQuietly(bytes);
        // 整代图一次发布：并发请求不会看到「json 已就绪、bytes 还是 null」的中间态。
        p.payload = new ProjectState.Payload(json, bytes, gz, rels, poms);
        GraphStore gs = graphStore();
        if (gs != null) {
            File f = gs.save(new File(info.rootDir).getName(), p.key, json);
            if (f != null) {
                Log.info("store", "扫描产物已写入 " + f);
            }
        }
    }

    /**
     * 从磁盘把上次扫出来的图读回内存（重启后打开项目时用）。
     *
     * <p>只填 {@link ProjectState#payload}；内存里的 {@link Graph} 不重建 ——
     * 服务端不做任何图查询（决策 D9），反序列化一整套 {@code Graph} 只为再序列化一次
     * 是纯浪费。模块编号 → 目录/实际 POM 的映射从已持久化的
     * {@code modules[].relPath} / {@code modules[].pomPath} 同时恢复，与 {@code occ[].m} 同源。
     *
     * @return 成功读回返回 {@code true}
     */
    public boolean loadFromDisk(ProjectState p) {
        GraphStore gs = graphStore();
        if (gs == null || p == null || p.payload != null) {
            return p != null && p.payload != null;
        }
        String json = gs.load(p.name, p.key);
        if (json == null) {
            return false;
        }
        byte[] bytes = utf8(json);
        byte[] gz = bytes == null ? null : Gzip.compressQuietly(bytes);
        String[] rels = GraphJsonWriter.relPathsOf(json);
        String[] poms = GraphJsonWriter.pomPathsOf(json);
        p.payload = new ProjectState.Payload(json, bytes, gz,
                rels == null ? new String[0] : rels, poms == null ? new String[0] : poms);
        Log.info("store", "已从磁盘恢复 " + p.name + " 的扫描产物（"
                + (bytes == null ? 0 : bytes.length) + " 字节）");
        return true;
    }

    private static byte[] utf8(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return null;
        }
    }

    /**
     * 组装元数据条要显示的运行信息。
     *
     * <p>这些字段前端原样显示（{@code views.js renderMeta}），留空就会出现
     * 「Maven · JDK · 插件 · - · 0.0s」这种空壳。所以扫描时实测到的值必须都填上。
     *
     * @param req <b>实际跑过的</b>请求。离线失败自动联网重试时，调用方必须传重试用的那个，
     *            否则会显示「在线」却配一条带 {@code -o} 的命令
     */
    static GraphJsonWriter.ProjectMeta metaOf(ProjectInfo info, ScanRequest req,
                                              RunInfo run) {
        GraphJsonWriter.ProjectMeta meta = new GraphJsonWriter.ProjectMeta();
        meta.id = info.rootDir;
        meta.name = new File(info.rootDir).getName();
        meta.rootDir = info.rootDir;
        meta.maven = run.mavenVersion;
        meta.jdk = run.jdkVersion;
        meta.plugin = req.pluginVersion;
        meta.profiles = req.profiles;
        meta.offline = req.offline;
        meta.xmx = run.xmx;
        // 记生效值而不是配置原文：用户填 "1" 或 "off" 时命令里没有 -T，这里也该是空串
        meta.threads = req.effectiveThreads();
        meta.declaredDependencyCount = info.declaredDependencyCount();
        meta.seconds = run.seconds;
        // 真实执行过的命令，不是前端按字段拼出来的近似版
        meta.command = MvnCommand.display(req);
        return meta;
    }

    // ------------------------------------------------------------ 扫描执行

    /** 异步跑一次扫描。回调里更新 {@link Job}。 */
    public void startScan(final Job job, final File dir, final ScanRequest base,
                          final File outDir, final ScanPipeline pipeline) {
        final ScanProgress progress = new ScanProgress(job, graphStore(), base);
        Thread t = new Thread(() -> {
            // tree 必须在 finally 里还拿得到：ScanResult 在 try 内就出了作用域
            File tree = null;
            try {
                ScanPipeline.ScanResult r = pipeline.scan(dir, outDir, base, progress,
                        job.canceller);
                tree = r.treeFile;
                ScanRun.apply(this, job, base, r);
            } catch (Exception e) {
                job.status = "failed";
                job.errorCode = "INTERNAL";
                job.error = String.valueOf(e.getMessage());
            } catch (Throwable err) {
                // `catch (Exception)` 抓不到 StackOverflowError（Error 不是 Exception）。
                // 漏掉它时的表现很糟：finally 把 job 标成 finished，但 status 还是 running、
                // error 还是 null，前端每 400ms 轮询一次永远看到「正在分析」，直到 job TTL 清掉。
                job.status = "failed";
                job.errorCode = "INTERNAL";
                job.error = String.valueOf(err.getClass().getSimpleName()
                        + (err.getMessage() == null ? "" : ": " + err.getMessage()));
            } finally {
                job.finishedAt = System.currentTimeMillis();
                job.progress = 1;
                // 日志留档失败也必须归还任务目录和调度槽位。
                try {
                    progress.finish(job.status, job.error, tree);
                } finally {
                    try {
                        JobDirs.finish(outDir);
                    } finally {
                        jobs.finish(job);
                    }
                }
            }
        }, "scan-" + job.id);
        t.setDaemon(true);
        t.start();
    }

    /**
     * 从已知项目表里摘掉一个（前端「最近打开」的删除按钮）。
     *
     * <p>只删记录，不碰磁盘上的任何文件 —— 用户点「×」的意思是「别再列出来」，
     * 不是「删掉我的项目」。
     *
     * @return 之前是否存在
     */
    public synchronized boolean remove(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        boolean removed = byKey.remove(key) != null;
        ProjectStore current = store();
        if (current != null) {
            current.remove(key);
        }
        return removed;
    }
}
