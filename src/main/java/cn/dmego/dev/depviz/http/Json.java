package cn.dmego.dev.depviz.http;

import cn.dmego.dev.depviz.discover.ProjectInfo;
import cn.dmego.dev.depviz.json.JsonOut;
import cn.dmego.dev.depviz.model.Module;

import java.util.List;

/** 几个响应的序列化。字段名被前端钉死，不能随便改。 */
final class Json {

    private Json() {
    }

    static String s(String v) {
        return JsonOut.quote(v);
    }

    /** {@code GET /api/discover}：字段见 02 §6.5。 */
    static String discover(ProjectInfo info) {
        JsonOut o = new JsonOut();
        o.beginObject();
        o.field("selectedDir", info.selectedDir);
        o.field("rootDir", info.rootDir);
        o.field("rootPom", info.rootPom);
        o.field("relocated", info.relocated);
        o.field("moduleCount", info.moduleCount());
        o.field("declaredDependencyCount", info.declaredDependencyCount());
        o.field("discoverMillis", info.discoverMillis);
        o.name("modules").beginArray();
        for (Module m : info.modules) {
            o.beginObject();
            o.field("groupId", m.groupId).field("artifactId", m.artifactId)
                    .field("packaging", m.packaging).field("relPath", m.relPath)
                    .field("declaredDeps", m.declaredDeps).field("childCount", m.childCount);
            // declared[] 是确认页「声明依赖」弹窗的数据源，条目形状与图中模块的 declared[] 一致。
            // optional 保持原始 XML 字符串 ""/"true"。
            o.name("declared").beginArray();
            for (Module.Declared d : m.declared) {
                o.beginObject();
                o.field("groupId", d.groupId).field("artifactId", d.artifactId)
                        .field("version", d.version).field("scope", d.scope)
                        .field("optional", d.optional);
                o.endObject();
            }
            o.endArray();
            o.endObject();
        }
        o.endArray();
        o.endObject();
        return o.toString();
    }

    /** {@code GET /api/scan/status}：{@code logLines} 从客户端给的 {@code offset} 起增量返回。 */
    static String status(Job job, int offset) {
        List<String> lines = job.linesSince(offset, 200);
        int nextOffset = offset + lines.size();
        JsonOut o = new JsonOut();
        o.beginObject();
        o.field("jobId", job.id);
        o.field("status", job.status);
        o.field("stage", job.stage);
        o.field("elapsedMs", job.elapsedMs());
        o.field("progress", job.progress);
        o.field("logOffset", nextOffset);
        // 完整日志的落盘位置。失败面板要把它显示给用户 —— 界面里那 200 行是残卷，
        // 真正的现场（Maven 每一行、树文件）在 data/<项目名>/ 下。没落盘时给空串，
        // 前端按真值判断，不必区分 null 与缺字段。
        o.field("logFile", job.logFile == null ? "" : job.logFile);
        o.name("logLines").beginArray();
        for (String l : lines) {
            o.value(l);
        }
        o.endArray();
        o.name("warnings").beginArray();
        for (String w : job.warnings) {
            o.value(w);
        }
        o.endArray();
        if (job.error != null) {
            // 文档约定 error 是对象 {error,message,detail}，不是裸字符串
            o.name("error").beginObject();
            o.field("error", job.errorCode == null ? "SCAN_FAILED" : job.errorCode);
            o.field("message", job.error);
            // detail 放日志尾部（Maven 的真实失败原因），最多 200 行
            o.field("detail", job.tailText(200));
            o.endObject();
        } else {
            o.name("error").nullValue();
        }
        if ("done".equals(job.status)) {
            o.field("projectId", job.projectKey);
        }
        o.endObject();
        return o.toString();
    }

    /** {@code GET /api/projects}：{@code when} 是服务端渲染好的短字符串。 */
    static String projects(List<ProjectState> all) {
        JsonOut o = new JsonOut();
        o.beginArray();
        for (ProjectState p : all) {
            o.beginObject();
            o.field("key", p.key);
            o.field("name", p.name);
            o.field("moduleCount", p.moduleCount);
            o.field("when", whenText(p.when));
            o.field("rootDir", p.key);
            // 有一代图就算扫过。重启后图 JSON 从 data/<项目名>/graph.json 读回来，
            // 点进去直接能看；前端据此决定「进图」还是「落到确认页重扫」，
            // 少了这个字段只能靠 /api/graph 的 404 试错。
            o.field("scanned", p.payload != null);
            o.endObject();
        }
        o.endArray();
        return o.toString();
    }

    /**
     * 相对时间由服务端渲染 —— 前端 {@code views.js} 直接显示这个串，
     * 不再做日期格式化。
     */
    static String whenText(long millis) {
        long gap = System.currentTimeMillis() - millis;
        if (gap < 60_000L) {
            return "刚刚";
        }
        if (gap < 3_600_000L) {
            return (gap / 60_000L) + " 分钟前";
        }
        if (gap < 86_400_000L) {
            return (gap / 3_600_000L) + " 小时前";
        }
        return (gap / 86_400_000L) + " 天前";
    }
}
