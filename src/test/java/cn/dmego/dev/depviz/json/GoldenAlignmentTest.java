package cn.dmego.dev.depviz.json;

import cn.dmego.dev.depviz.config.JsonIn;
import cn.dmego.dev.depviz.discover.Discoverer;
import cn.dmego.dev.depviz.discover.ProjectInfo;
import cn.dmego.dev.depviz.model.Graph;
import cn.dmego.dev.depviz.parse.TreeParser;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Java 解析器 + {@link GraphJsonWriter} 必须与手写的 {@code golden/multi.json} 逐字段一致。
 *
 * <p>19 行树、4 个 POM 和 golden 均在仓库内。树根顺序故意与磁盘发现顺序不同，
 * 以检查 {@code modules[]} 与 {@code occ[].m} 的对齐；数组顺序、可选字段和 project 元数据
 * 一并比较。运行路径使用固定相对值，使 golden 不依赖工作目录的绝对路径。
 */
public class GoldenAlignmentTest {

    @Test
    public void multiMatchesGolden() throws Exception {
        File dir = new File("src/test/resources/projects/multi");
        File tree = new File("src/test/resources/tree/multi.txt");
        File golden = new File("src/test/resources/golden/multi.json");
        assertTrue("仓库内 multi POM 必须存在", new File(dir, "pom.xml").isFile());
        assertTrue("仓库内 tree fixture 必须存在", tree.isFile());
        assertTrue("仓库内手写 golden 必须存在", golden.isFile());

        ProjectInfo info = new Discoverer().discover(dir);
        List<String> diskOrder = new ArrayList<String>();
        for (cn.dmego.dev.depviz.model.Module module : info.modules) {
            diskOrder.add(module.artifactId);
        }
        assertEquals("磁盘发现顺序必须与树根顺序不同",
                java.util.Arrays.asList("multi-parent", "app", "util", "lib"), diskOrder);
        Graph graph = TreeParser.parseFile(tree.toPath());
        GraphJsonWriter.ProjectMeta meta = new GraphJsonWriter.ProjectMeta();
        meta.id = "multi";
        meta.name = "multi";
        meta.rootDir = "projects/multi";
        meta.declaredDependencyCount = info.declaredDependencyCount();
        String mineJson = GraphJsonWriter.write(graph, meta, info.modules);

        Object mine = JsonIn.parse(mineJson);
        Object expected = JsonIn.parse(new String(
                Files.readAllBytes(golden.toPath()), StandardCharsets.UTF_8));
        List<String> diffs = new ArrayList<String>();
        walk(expected, mine, "", diffs);
        assertEquals("multi：与 golden 必须逐字段一致，差异 " + diffs.size()
                + " 处\n" + join(diffs, 20), 0, diffs.size());
    }

    /** 递归比较全部键、数组顺序和标量值。 */
    private static void walk(Object a, Object b, String path, List<String> diffs) {
        if (a instanceof Map && b instanceof Map) {
            Map<?, ?> ma = (Map<?, ?>) a;
            Map<?, ?> mb = (Map<?, ?>) b;
            Set<String> ka = keys(ma);
            Set<String> kb = keys(mb);
            for (String k : ka) {
                if (!kb.contains(k)) {
                    diffs.add(path + "/" + k + " 仅在 golden 中");
                }
            }
            for (String k : kb) {
                if (!ka.contains(k)) {
                    diffs.add(path + "/" + k + " 仅在 Java 输出中");
                }
            }
            for (String k : ka) {
                if (kb.contains(k)) {
                    walk(ma.get(k), mb.get(k), path + "/" + k, diffs);
                }
            }
            return;
        }
        if (a instanceof Collection && b instanceof Collection) {
            List<?> la = new ArrayList<Object>((Collection<?>) a);
            List<?> lb = new ArrayList<Object>((Collection<?>) b);
            if (la.size() != lb.size()) {
                diffs.add(path + " 长度 " + la.size() + " vs " + lb.size());
            }
            for (int i = 0; i < Math.min(la.size(), lb.size()); i++) {
                walk(la.get(i), lb.get(i), path + "[" + i + "]", diffs);
            }
            return;
        }
        if (!sameScalar(a, b)) {
            diffs.add(path + " = " + a + " vs " + b);
        }
    }

    private static Set<String> keys(Map<?, ?> m) {
        Set<String> out = new TreeSet<String>();
        for (Object k : m.keySet()) {
            out.add(String.valueOf(k));
        }
        return out;
    }

    /** JSON 数字统一是 Double，用数值比较避免 1 与 1.0 的假差异。 */
    private static boolean sameScalar(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        return a == null ? b == null : a.equals(b);
    }

    private static String join(List<String> items, int limit) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(items.size(), limit); i++) {
            sb.append("\n  ").append(items.get(i));
        }
        return sb.toString();
    }

    /** 并行度（Maven `-T`）要进 {@code project}，元数据条与详情弹窗读的是它。 */
    @Test
    public void threadsReachTheProjectMeta() throws Exception {
        GraphJsonWriter.ProjectMeta meta = new GraphJsonWriter.ProjectMeta();
        meta.threads = "4C";
        Graph graph = TreeParser.parseFile(
                new File("src/test/resources/tree/multi.txt").toPath());
        String json = GraphJsonWriter.write(graph, meta,
                java.util.Collections.<cn.dmego.dev.depviz.model.Module>emptyList());
        assertTrue(json.contains("\"threads\":\"4C\""));
    }

    /** 有 warning 不等于图里有环，graphMode 不能从 incomplete 推导。 */
    @Test
    public void graphModeIsIndependentOfIncomplete() throws Exception {
        GraphJsonWriter.ProjectMeta meta = new GraphJsonWriter.ProjectMeta();
        meta.incomplete = true;
        meta.graphMode = "TREE";
        Graph graph = TreeParser.parseFile(
                new File("src/test/resources/tree/multi.txt").toPath());
        String json = GraphJsonWriter.write(graph, meta, java.util.Collections.<cn.dmego.dev.depviz.model.Module>emptyList());
        assertTrue("有 warning 不等于图里有环", json.contains("\"graphMode\":\"TREE\""));
        assertTrue(json.contains("\"incomplete\":true"));
    }
}
