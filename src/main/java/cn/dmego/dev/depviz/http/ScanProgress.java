package cn.dmego.dev.depviz.http;

import cn.dmego.dev.depviz.discover.ProjectInfo;
import cn.dmego.dev.depviz.log.Log;
import cn.dmego.dev.depviz.scan.MvnCommand;
import cn.dmego.dev.depviz.scan.ScanPipeline;
import cn.dmego.dev.depviz.scan.ScanRequest;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 一次扫描的进度回调 + 落盘：把 Maven 的原始输出写到 {@code data/<项目名>/maven.log}。
 *
 * <p>为什么不把这些直接塞进 {@link Projects#startScan} 的匿名类里：那段代码已经是
 * 「起线程 + 抓异常 + 收尾」，再叠上开文件、写头、算路径会变成没人愿意读的长方法。
 * 这里只管一件事 —— 把 {@link ScanPipeline.Progress} 的三个回调落到两处：
 * 内存里的 {@link Job}（前端轮询看）和项目目录里的文件（失败后事后看）。
 *
 * <p>为什么要落盘：{@code jobs/<jobId>/} 是临时目录，扫描一结束整个删掉，
 * 而用户看到的失败提示只有一行「解析依赖树失败: 第 117 行无法解析」。
 * 真正的原因（Maven 报了什么、树文件第 117 行长什么样）全在那些被删掉的文件里。
 */
final class ScanProgress implements ScanPipeline.Progress {

    /** 阶段 → 粗粒度进度。没有真实进度可报，但进度条不能全程 0%。 */
    private static final Map<String, Double> PROGRESS_OF = new HashMap<String, Double>();

    static {
        // 权重按实测耗时给：resolve（跑 Maven）占大头，parse/write 很快。
        PROGRESS_OF.put("DISCOVER", 0.05);
        PROGRESS_OF.put("RESOLVE", 0.40);
        PROGRESS_OF.put("PARSE", 0.90);
        PROGRESS_OF.put("WRITE", 1.0);
    }

    private static final String TREE_NAME = "tree.txt";

    private final Job job;
    /** 为 {@code null} 时不落盘（单元测试里没有配置）。 */
    private final GraphStore store;
    private final ScanRequest base;

    /** 两个可能为 {@code null} 的输出。 */
    private ScanLog log;
    private File projectDir;
    private int fileLines;

    ScanProgress(Job job, GraphStore store, ScanRequest base) {
        this.job = job;
        this.store = store;
        this.base = base;
    }

    @Override
    public void stage(ScanPipeline.Stage s) {
        job.stage = s.name().toLowerCase(Locale.ROOT);
        job.progress = PROGRESS_OF.getOrDefault(s.name(), job.progress);
    }

    @Override
    public void log(String line) {
        job.addLog(line);
        if (log != null) {
            log.line(line);
            fileLines++;
        }
    }

    /**
     * 发现完成：确定项目目录并打开日志文件。
     *
     * <p>必须在这里就把文件开好 —— 否则 Maven 的前几十行（含「下载失败」
     * 这类真正的死因）会在 {@link #log} 被调用时因为还没有文件而丢掉。
     */
    @Override
    public void project(ProjectInfo info) {
        if (store == null || info == null) {
            return;
        }
        String name = new File(info.rootDir).getName();
        projectDir = store.dirForProject(name, info.rootDir, true);
        if (projectDir == null) {
            Log.warn("scan", "没有可写的项目目录，本次扫描日志不落盘（项目=" + name + "）");
            return;
        }
        log = ScanLog.open(new File(projectDir, ScanLog.FILE_NAME));
        if (log == null) {
            return;
        }
        job.logFile = log.file().getAbsolutePath();
        log.note("depviz 扫描日志 " + now());
        log.note("项目   " + info.rootDir);
        if (!info.rootDir.equals(info.selectedDir)) {
            // 在子模块上点开的多模块项目：写清楚真正的根，否则用户对着
            // 一个不存在的路径找 pom.xml
            log.note("所选   " + info.selectedDir + "（已上移到模块根）");
        }
        log.note("模块   " + info.moduleCount() + " 个");
        log.note("命令   " + MvnCommand.display(base));
        log.note("说明   下面开始是 Maven 的原始输出，未做任何过滤");
    }

    /**
     * 结束：写结论行、保留依赖树、关文件。
     *
     * @param status {@link Job#status}，即 done / failed / cancelled
     * @param error  失败原因（{@link Job#error}），成功时为 {@code null}
     * @param treeFile 本次的依赖树（可能不存在）
     */
    void finish(String status, String error, File treeFile) {
        File kept = keepTree(treeFile);
        if (log == null) {
            return;
        }
        log.note("");
        if ("done".equals(status)) {
            log.note("扫描完成，Maven 输出共 " + fileLines + " 行");
        } else if ("cancelled".equals(status)) {
            log.note("扫描已取消（已读到 " + fileLines + " 行）");
        } else {
            log.note("扫描失败: " + (error == null ? "未知原因" : error));
            if (kept != null) {
                // 解析失败时用户第一个想看的不是 Maven 输出，而是树文件里那一行
                log.note("依赖树已保留: " + kept.getAbsolutePath());
            }
        }
        log.close();
        log = null;
    }

    /**
     * 把临时目录里的依赖树复制到项目目录，随后临时目录会被整个删掉。
     *
     * <p>失败的那次尤其要留：解析报错说的是「第 117 行」，而那一行只存在于这个文件里。
     *
     * @return 复制后的文件；没有树文件或复制失败返回 {@code null}
     */
    private File keepTree(File treeFile) {
        if (projectDir == null || treeFile == null || !treeFile.isFile()) {
            return null;
        }
        File target = new File(projectDir, TREE_NAME);
        try {
            Files.copy(treeFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            Log.warn("scan", "保留依赖树失败 " + target + ": " + e.getMessage());
            return null;
        }
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date());
    }
}
