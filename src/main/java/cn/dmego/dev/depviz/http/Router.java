package cn.dmego.dev.depviz.http;

import cn.dmego.dev.depviz.discover.DiscoverException;
import cn.dmego.dev.depviz.discover.ProjectInfo;
import cn.dmego.dev.depviz.config.ConfigStore;
import cn.dmego.dev.depviz.scan.ScanPipeline;
import cn.dmego.dev.depviz.scan.ScanRequest;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * 路由分发。10 条路由（含 `/api/health`），全部是「读文件 / 起进程 / 返回 JSON」这类短动作。
 *
 * <p>没有 {@code /api/search}、{@code /api/paths}、{@code /api/conflicts} —— 查询全部在
 * 浏览器里跑（决策 D9）。后端不做任何图算法。
 */
public final class Router {

    private final ConfigStore config;
    private final ConfigRoutes configRoutes;
    private final Projects projects;
    private final ScanPipeline pipeline = new ScanPipeline();

    /** 生产构造：读写 {@code ~/.depviz/}。 */
    public Router() {
        this(ConfigStore.defaultLocation());
    }

    /**
     * 可注入配置位置的构造。
     *
     * <p><b>测试必须用这个</b>：默认构造会读写用户真实的 {@code ~/.depviz/config.json}，
     * 跑一遍单元测试就把人家的 Maven 路径改掉（踩过）。
     *
     * <p>{@link Projects} 拿的是配置对象的<b>活引用</b>而不是快照：设置面板里改完
     * 数据目录，下一次扫描就该写进新目录。早先在这里固化 {@code dataDirFile()}，
     * 用户改完保存成功却毫无效果，重启才发现。
     *
     * @param store 配置文件位置（测试传临时目录）
     */
    public Router(ConfigStore store) {
        this.config = store.load();
        this.configRoutes = new ConfigRoutes(this.config);
        this.projects = Projects.forConfig(this.config);
    }

    public static Router defaultRouter() {
        Router r = new Router();
        r.projects.restore();
        // 启动时清上次进程留下的作业目录：进程被强杀时 finally 不执行，树文件会留下来
        JobDirs.cleanStale(r.config.dataDirFile());
        return r;
    }

    /** 测试专用：临时目录下的 Router，绝不碰 {@code ~/.depviz}。 */
    static Router testRouter(ConfigStore store) {
        return new Router(store);
    }

    public void handle(Exchange e) throws IOException {
        String p = e.path();
        String m = e.method();
        try {
            if (!p.startsWith("/api/")) {
                // 非 API 一律当静态资源：index.html 用相对路径引 css/js，请求会落在根上
                Static.handle(e, p);
                return;
            }
            if ("/api/discover".equals(p)) {
                discover(e);
            } else if ("/api/scan".equals(p) && "POST".equals(m)) {
                scan(e);
            } else if ("/api/scan/status".equals(p)) {
                scanStatus(e);
            } else if ("/api/scan/cancel".equals(p) && "POST".equals(m)) {
                scanCancel(e);
            } else if ("/api/graph".equals(p)) {
                graph(e);
            } else if ("/api/pom".equals(p)) {
                pom(e);
            } else if ("/api/projects".equals(p)) {
                if ("DELETE".equals(m)) {
                    deleteProject(e);
                } else {
                    listProjects(e);
                }
            } else if ("/api/config".equals(p)) {
                if ("POST".equals(m)) {
                    configRoutes.save(e);
                } else {
                    configRoutes.get(e);
                }
            } else if ("/api/list-dir".equals(p)) {
                listDir(e);
            } else if ("/api/health".equals(p)) {
                e.json(200, "{\"ok\":true}");
            } else {
                e.error(404, "NOT_FOUND", "没有这个接口: " + p);
            }
        } catch (DiscoverException ex) {
            e.error(400, "BAD_REQUEST", ex.getMessage());
        } catch (IllegalArgumentException ex) {
            e.error(400, "BAD_REQUEST", ex.getMessage());
        } catch (IOException ex) {
            e.error(500, "INTERNAL", String.valueOf(ex.getMessage()));
        }
    }

    /** 给退出钩子用：找当前正在跑的扫描。 */
    Projects projects() {
        return projects;
    }

    // ------------------------------------------------------------- handlers

    private void discover(Exchange e) throws IOException, DiscoverException {
        String dir = e.q("dir");
        if (dir == null || dir.isEmpty()) {
            throw new IllegalArgumentException("缺少 dir 参数");
        }
        ProjectInfo info = pipeline.discover(new File(Paths.canonicalDir(dir)),
                java.util.Arrays.asList(config.profileList()));
        e.json(200, Json.discover(info));
    }

    private void scan(Exchange e) throws IOException {
        String dir = e.q("dir");
        if (dir.isEmpty()) {
            throw new IllegalArgumentException("缺少 dir 参数");
        }
        // 必须先规范化再校验：相对路径 / 空路径要在这里就 400 掉，
        // 不能等响应发出去之后才发现（那时异常只能变成一条 console 报错）。
        File target = new File(Paths.canonicalDir(dir));
        String canonicalKey = target.getPath();
        // 抢占槽位必须**原子**：先 runningJob() 再 register() 中间隔着 canonicalDir()
        // 与 mkdirs()，约 1 ms 的窗口里两个并发 POST 会双双 200 并起两个 Maven
        // （threading.Barrier 实测 19/20 次双开）。tryAcquire 用 CAS 抢占。
        String jobId = projects.jobRegistry().nextJobId();
        Job job = projects.jobRegistry().tryAcquire(jobId, canonicalKey);
        if (job == null) {
            Job busy = projects.jobRegistry().runningJob();
            e.error(409, "SCAN_IN_PROGRESS",
                    "已有扫描在进行（" + (busy == null ? "?" : busy.id) + "），请等它结束");
            return;
        }
        projects.jobRegistry().commit(job);
        // 目录名必须与 jobId 解耦：jobId 只是进程内自增的 j1，两个共用同一 dataDir 的
        // 实例首个任务都叫 j1，会往同一个 tree.txt 里写、并互相删掉对方的输出。
        File outDir = JobDirs.newJobDir(config.dataDirFile());
        if (outDir == null) {
            // 建目录失败必须回滚，否则这个永远起不来的 job 会占着槽位
            projects.jobRegistry().release(job);
            e.error(500, "INTERNAL", "无法创建任务目录（检查数据目录是否可写）: "
                    + new File(config.dataDirFile(), "jobs"));
            return;
        }
        // 启动后由工作线程的 finally 唯一负责回收。客户端连接不拥有后台任务的生命周期。
        try {
            ScanRequest request = buildRequest(e, target, outDir);
            projects.startScan(job, target, request, outDir, pipeline);
        } catch (RuntimeException | Error ex) {
            projects.jobRegistry().release(job);
            JobDirs.finish(outDir);
            throw ex;
        }
        e.json(200, "{\"jobId\":" + Json.s(job.id) + "}");
    }

    /** 把「请求里的临时覆盖」叠到「配置的默认值」上，组装出这一次的扫描参数。 */
    private ScanRequest buildRequest(Exchange e, File target, File outDir) {
        ScanRequest base = new ScanRequest.Builder()
                .projectDir(target)
                .outputFile(new File(outDir, "tree.txt"))
                .offline(e.qBool("offline", config.offline()))
                .mavenExecutable(config.maven())
                .javaHome(config.javaHome())
                .settingsXml(config.settingsXml())
                .localRepo(config.localRepo())
                .pluginVersion(e.q("plugin", ScanRequest.DEFAULT_PLUGIN_VERSION))
                // auto 时按物理内存分档；用户显式配了就听他配的（01 §6.1）
                .xmx(config.resolvedXmx())
                .threads(config.threads())
                .build();
        for (String prof : config.profileList()) {
            base = base.newBuilder().profile(prof).build();
        }
        return base;
    }

    private void scanStatus(Exchange e) throws IOException {
        Job job = projects.jobRegistry().job(e.q("jobId"));
        if (job == null) {
            e.error(404, "JOB_NOT_FOUND", "任务不存在");
            return;
        }
        e.json(200, Json.status(job, e.qInt("offset", 0)));
    }

    private void scanCancel(Exchange e) throws IOException {
        Job job = projects.jobRegistry().job(e.q("jobId"));
        if (job == null) {
            e.error(404, "JOB_NOT_FOUND", "任务不存在");
            return;
        }
        job.canceller.cancel();
        e.json(200, "{\"ok\":true}");
    }

    private void graph(Exchange e) throws IOException {
        ProjectState p = projects.project(e.q("projectId"));
        // 取一次快照：并发的重扫会整体替换 payload，分两次读可能读到不同代的数据。
        ProjectState.Payload payload = p == null ? null : p.payload;
        if (payload == null) {
            e.error(404, "PROJECT_NOT_FOUND", "项目没有扫描结果");
            return;
        }
        e.bytes(200, "application/json; charset=utf-8", Gzip.maybe(e, payload.bytes, payload.gzip));
    }

    private void pom(Exchange e) throws IOException {
        ProjectState p = projects.project(e.q("projectId"));
        // 模块用下标而不是 artifactId：跨 groupId 可能有同名模块，用名字查会有歧义
        int moduleId = e.qInt("moduleId", -1);
        if (p == null) {
            e.error(404, "PROJECT_NOT_FOUND", "项目不存在");
            return;
        }
        PomText text = PomText.read(p, moduleId);
        if (text == null) {
            e.error(404, "POM_NOT_FOUND", "读不到 POM 文件（模块下标 " + moduleId
                    + " 越界、元数据缺失或路径不在项目内）");
            return;
        }
        e.json(200, "{\"moduleId\":" + moduleId
                + ",\"relPath\":" + Json.s(text.relPath)
                + ",\"pomPath\":" + Json.s(text.pomPath)
                + ",\"pomText\":" + Json.s(text.text) + "}");
    }

    private void listProjects(Exchange e) throws IOException {
        List<ProjectState> all = projects.all();
        e.json(200, Json.projects(all));
    }

    /** 删除一条最近记录（参数 {@code id} 就是 {@code GET /api/projects} 里的 {@code key}）。 */
    private void deleteProject(Exchange e) throws IOException {
        String key = e.q("id");
        if (key.isEmpty()) {
            e.error(400, "BAD_REQUEST", "缺少参数 id");
            return;
        }
        if (!projects.remove(key)) {
            e.error(404, "PROJECT_NOT_FOUND", "没有这个项目记录: " + key);
            return;
        }
        e.json(200, "{\"ok\":true}");
    }

    /** 列一层目录，给页内目录浏览器用（{@code GET /api/list-dir?path=}）。 */
    private void listDir(Exchange e) throws IOException {
        DirList.respond(e, e.q("path"));
    }

    /** 只给测试用。 */
    Projects projectsForTest() {
        return projects;
    }

    ConfigStore configForTest() {
        return config;
    }

}
