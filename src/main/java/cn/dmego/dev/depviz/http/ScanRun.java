package cn.dmego.dev.depviz.http;

import cn.dmego.dev.depviz.env.EnvProbe;
import cn.dmego.dev.depviz.json.GraphJsonWriter;
import cn.dmego.dev.depviz.scan.ScanPipeline;
import cn.dmego.dev.depviz.scan.ScanRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次扫描结束后「把结果落到该落的地方」。
 *
 * <p>从 {@link Projects} 拆出来是为了让职责清晰：{@code Projects} 本身管注册表、
 * 缓存与持久化，而这一段的职责是**翻译一次扫描的终态** —— 成功要建项目条目、算元数据、
 * 缓存图、写盘，失败/取消只改 job 状态。两者改动的理由不一样，放一起会各自被对方的
 * 细节挤得看不清。
 */
final class ScanRun {

    private ScanRun() {
    }

    /**
     * 把一次扫描的终态写回 {@link Job} 与项目表。
     *
     * <p>失败与取消**不建项目**：用户看到「最近打开」里多出一条从没成功过的记录，
     * 点进去只会得到 404。
     */
    static void apply(Projects projects, Job job, ScanRequest base, ScanPipeline.ScanResult r) {
        if (r.cancelled) {
            job.status = "cancelled";
            return;
        }
        if (!r.ok) {
            job.status = "failed";
            job.errorCode = "SCAN_FAILED";
            job.error = r.message;
            return;
        }
        // 元数据必须描述**实际发生的事**：离线请求自动联网重试成功后其实是联网跑的，
        // 命令里也没有 -o。用 base 会让界面一边说「在线」一边显示一条带 -o 的命令。
        ScanRequest ran = r.request == null ? base : r.request;
        ProjectState p = projects.open(r.info);
        List<String> warns = warnings(r);
        GraphJsonWriter.ProjectMeta meta = projects.metaOf(r.info, ran, runInfo(ran, r));
        meta.warnings = warns;
        meta.incomplete = !warns.isEmpty();
        projects.storeGraph(p, r.graph, r.info, meta);
        job.projectKey = p.key;
        job.warnings = warns;
        job.status = "done";
    }

    /**
     * 扫描有缺件时才有一条警告 —— 它是元数据条变黄与「联网重试」按钮的唯一触发条件。
     *
     * <p>文案要区分实际模式：联网跑还缺件说明是网络抖动或私有仓库要凭证，再让人
     * 「联网后重试」只会把他绕回原地（他刚刚就是联网跑的）。
     */
    private static List<String> warnings(ScanPipeline.ScanResult r) {
        List<String> warns = new ArrayList<String>();
        if (r.missingCount <= 0) {
            return warns;
        }
        if (r.online()) {
            warns.add(r.missingCount + " 个依赖未能解析（缺 POM，其子树缺失）"
                    + "，可能是网络不稳定或私有仓库需要凭证");
        } else {
            warns.add(r.missingCount + " 个依赖在离线模式下未能解析（缺 POM，其子树缺失），可联网后重试");
        }
        return warns;
    }

    /**
     * 实测到的 Maven / JDK 版本与堆上限 —— 空着会渲染成「Maven · JDK · 插件 · - · 0.0s」。
     *
     * <p>堆上限取的是<b>实际请求里的值</b>（{@code base.xmx}），不是再算一次：
     * 早先这里重新调 {@code EnvProbe.xmxForThisMachine()}，于是用户在配置里写
     * {@code 512m}、界面却显示分档算出来的 {@code 1024m}，两个数都不是一回事。
     */
    private static RunInfo runInfo(ScanRequest base, ScanPipeline.ScanResult r) {
        return new RunInfo.Builder()
                .mavenVersion(EnvProbe.mavenVersion(base.mavenExecutable))
                .jdkVersion(EnvProbe.javaVersion(base.javaHome))
                .xmx(base.xmx)
                .seconds(r.millis / 1000.0)
                .build();
    }
}
