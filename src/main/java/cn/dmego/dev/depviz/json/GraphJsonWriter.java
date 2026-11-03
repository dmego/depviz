package cn.dmego.dev.depviz.json;

import cn.dmego.dev.depviz.model.Artifact;
import cn.dmego.dev.depviz.model.Graph;
import cn.dmego.dev.depviz.model.Module;
import cn.dmego.dev.depviz.model.Occurrence;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 生成前端查询引擎使用的 graph JSON，顶层键为 {@code project}、{@code gas}、{@code occ}、
 * {@code moduleRoots} 和 {@code modules}。
 *
 * <p>GA 与版本索引按首次出现顺序分配；模块不含 {@code pomText}，POM 由 {@code /api/pom} 从磁盘读取。
 */
public final class GraphJsonWriter {

    private GraphJsonWriter() {
    }

    /** 完整图 JSON。 */
    public static String write(Graph g, ProjectMeta meta, List<Module> modules) {
        JsonOut o = new JsonOut();
        o.beginObject();
        writeProject(o, g, meta);
        writeGas(o, g);
        writeOcc(o, g);
        writeModuleRoots(o, g);
        writeModules(o, g, modules);
        o.endObject();
        return o.toString();
    }

    private static void writeProject(JsonOut o, Graph g, ProjectMeta m) {
        o.name("project").beginObject();
        o.field("id", m.id);
        o.field("name", m.name);
        o.field("rootDir", m.rootDir);
        o.field("maven", m.maven);
        o.field("jdk", m.jdk);
        o.field("plugin", m.plugin);
        o.name("profiles").beginArray();
        for (String p : m.profiles) {
            o.value(p);
        }
        o.endArray();
        o.field("offline", m.offline);
        o.field("xmx", m.xmx);
        o.field("threads", m.threads);
        o.field("moduleCount", g.moduleCount());
        o.field("declaredDependencyCount", m.declaredDependencyCount);
        o.field("seconds", m.seconds);
        o.field("incomplete", m.incomplete);
        o.field("graphMode", m.graphMode);
        o.field("parseCoverage", m.parseCoverage);
        o.name("warnings").beginArray();
        for (String w : m.warnings) {
            o.value(w);
        }
        o.endArray();
        o.field("command", m.command);
        o.endObject();
    }

    private static void writeGas(JsonOut o, Graph g) {
        o.name("gas").beginArray();
        for (Artifact a : g.artifacts()) {
            o.beginObject();
            o.field("g", a.groupId);
            o.field("a", a.artifactId);
            o.name("v").beginArray();
            for (String v : a.versions) {
                o.value(v);
            }
            o.endArray();
            o.endObject();
        }
        o.endArray();
    }

    private static void writeOcc(JsonOut o, Graph g) {
        o.name("occ").beginArray();
        for (Occurrence c : g.occurrences()) {
            o.beginObject();
            o.field("i", c.id).field("p", c.parent).field("ga", c.gaId).field("v", c.versionId)
                    .field("e", c.effective ? 1 : 0).field("m", c.moduleId).field("t", c.type);
            if (!c.classifier.isEmpty()) {
                o.field("c", c.classifier);
            }
            if (!c.scope.isEmpty()) {
                o.field("s", c.scope);
            }
            if (!c.omitReason.isEmpty()) {
                o.field("o", c.omitReason);
            }
            if (!c.omittedBy.isEmpty()) {
                o.field("ob", c.omittedBy);
            }
            if (!c.versionManagedFrom.isEmpty()) {
                o.field("vm", c.versionManagedFrom);
            }
            if (!c.scopeManagedFrom.isEmpty()) {
                o.field("sm", c.scopeManagedFrom);
            }
            if (!c.scopeNotUpdatedTo.isEmpty()) {
                o.field("snu", c.scopeNotUpdatedTo);
            }
            if (!c.annotation.isEmpty()) {
                o.field("ann", c.annotation);
            }
            o.endObject();
        }
        o.endArray();
    }

    private static void writeModuleRoots(JsonOut o, Graph g) {
        o.name("moduleRoots").beginArray();
        for (int id : g.moduleRoots()) {
            o.value(id);
        }
        o.endArray();
    }

    private static void writeModules(JsonOut o, Graph g, List<Module> modules) {
        o.name("modules").beginArray();
        for (Module m : alignToModuleIds(g, modules)) {
            o.beginObject();
            o.field("groupId", m.groupId).field("artifactId", m.artifactId)
                    .field("packaging", m.packaging).field("relPath", m.relPath)
                    .field("pomPath", m.pomPath)
                    .field("declaredDeps", m.declaredDeps).field("childCount", m.childCount);
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
    }

    /**
     * 按图中的模块编号对齐磁盘发现的元数据，返回列表第 {@code k} 项对应 {@code occ[].m == k}。
     *
     * <p>按完整 GAV 匹配。无唯一匹配时保留占位槽，避免跳过模块导致后续索引错位；
     * 结果只包含本次 Maven 输出的模块根。
     */
    static List<Module> alignToModuleIds(Graph g, List<Module> modules) {
        Map<String, List<Integer>> byCoord = new LinkedHashMap<String, List<Integer>>();
        for (int i = 0; i < modules.size(); i++) {
            Module m = modules.get(i);
            String k = m.groupId + ':' + m.artifactId + ':' + m.version;
            List<Integer> idx = byCoord.get(k);
            if (idx == null) {
                idx = new ArrayList<Integer>();
                byCoord.put(k, idx);
            }
            idx.add(Integer.valueOf(i));
        }
        List<Module> out = new ArrayList<Module>(g.moduleCount());
        for (int id : g.moduleRoots()) {
            Occurrence root = g.occ(id);
            Artifact a = g.artifactOf(root);
            String version = a.versions.get(root.versionId);
            List<Integer> idx = byCoord.remove(a.groupId + ':' + a.artifactId + ':' + version);
            if (idx == null || idx.size() != 1) {
                out.add(placeholder(a, version));
                continue;
            }
            out.add(modules.get(idx.get(0).intValue()));
        }
        return out;
    }

    /**
     * 每个模块根对应的相对路径，下标与 {@code modules[]} / {@code occ[].m} 严格一致。
     *
     * <p>仅用于模块目录层级和展示；实际文件身份使用 {@link #pomPathsOf(Graph, List)}。
     * 空串表示未知目录，不能靠追加 {@code pom.xml} 猜测 POM 文件。
     */
    public static String[] relPathsOf(Graph g, List<Module> modules) {
        List<Module> aligned = alignToModuleIds(g, modules);
        String[] out = new String[aligned.size()];
        for (int i = 0; i < aligned.size(); i++) {
            String rel = aligned.get(i).relPath;
            out[i] = rel == null ? "" : rel;
        }
        return out;
    }

    /**
     * 从持久化 JSON 读取按模块编号排列的 {@code modules[].relPath}；重启后避免用磁盘发现顺序索引模块。
     *
     * @return JSON 无效时返回 {@code null}
     */
    public static String[] relPathsOf(String graphJson) {
        return modulePathsOf(graphJson, "relPath");
    }

    /** 实际 POM 文件路径，下标与图中模块编号一致；未知身份的槽为空。 */
    public static String[] pomPathsOf(Graph g, List<Module> modules) {
        List<Module> aligned = alignToModuleIds(g, modules);
        String[] paths = new String[aligned.size()];
        for (int i = 0; i < aligned.size(); i++) {
            paths[i] = aligned.get(i).pomPath;
        }
        return paths;
    }

    public static String[] pomPathsOf(String graphJson) {
        return modulePathsOf(graphJson, "pomPath");
    }

    private static String[] modulePathsOf(String graphJson, String field) {
        if (graphJson == null || graphJson.isEmpty()) {
            return null;
        }
        try {
            Object parsed = cn.dmego.dev.depviz.config.JsonIn.parse(graphJson);
            if (!(parsed instanceof Map)) {
                return null;
            }
            Object modules = ((Map<?, ?>) parsed).get("modules");
            if (!(modules instanceof List)) {
                return null;
            }
            List<?> list = (List<?>) modules;
            String[] out = new String[list.size()];
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                Object rel = item instanceof Map ? ((Map<?, ?>) item).get(field) : null;
                out[i] = rel instanceof String ? (String) rel : "";
            }
            return out;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 匹配不到元数据时的占位槽：坐标照抄图里的，其余留空。 */
    private static Module placeholder(Artifact a, String version) {
        return new Module(a.groupId, a.artifactId, version, "", "", "", 0, 0,
                java.util.Collections.<Module.Declared>emptyList());
    }

    /** 只有 {@code project} 里那些「运行元数据」，与图结构无关。 */
    public static final class ProjectMeta {
        public String id = "";
        public String name = "";
        public String rootDir = "";
        public String maven = "";
        public String jdk = "";
        public String plugin = "";
        public List<String> profiles = java.util.Collections.emptyList();
        public boolean offline = true;
        public String xmx = "";
        /** Maven {@code -T} 参数原值；空串表示串行，核数换算由 Maven 处理。 */
        public String threads = "";
        public int declaredDependencyCount;
        public double seconds;
        public boolean incomplete;
        /** 依赖图结构为 {@code "TREE"} 或 {@code "DAG"}；与扫描警告 {@link #incomplete} 独立。 */
        public String graphMode = "TREE";
        public double parseCoverage = 1.0;
        /** 本次执行的 Maven 参数（从 {@code -B} 起，不含可执行路径），供扫描详情原样显示。 */
        public String command = "";
        public List<String> warnings = java.util.Collections.emptyList();
    }

    /** 统计生效的非根 occurrence 数；不含模块根，供测试使用。 */
    public static int effectiveNonRoot(Graph g) {
        int n = 0;
        Set<Integer> roots = new HashSet<Integer>();
        for (int id : g.moduleRoots()) {
            roots.add(id);
        }
        for (Occurrence c : g.occurrences()) {
            if (c.effective && !roots.contains(c.id)) {
                n++;
            }
        }
        return n;
    }
}
