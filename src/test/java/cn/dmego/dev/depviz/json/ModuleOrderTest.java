package cn.dmego.dev.depviz.json;

import cn.dmego.dev.depviz.model.Graph;
import cn.dmego.dev.depviz.model.Module;
import cn.dmego.dev.depviz.parse.TreeParser;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * modules[] 的顺序必须由 occ 中 p === -1 的出现顺序决定，第 k 项就是 occ[].m == k 的模块。
 *
 * <p>两条独立的坑，各自都能让「计数全对、图全错」：
 * <ol>
 *   <li><b>按 artifactId 反查</b>：Maven 允许不同 groupId 下同名 artifactId（父子工程里的
 *       core / api / common），单键映射会把第二个同名模块静默吞掉。</li>
 *   <li><b>匹配不到就跳过</b>：只要有一个模块对不上（groupId 用了 {@code ${...}}、
 *       模块由 profile 引入、{@code <module>} 指向自定义文件），后面整体前移一位，
 *       occ[].m 指向别人的元数据，而模块总数一模一样。</li>
 * </ol>
 * 所以对齐只能「一个根一个槽」，匹配不上放占位槽，绝不允许挪位。
 */
public class ModuleOrderTest {

    /** 两模块同 artifactId、不同 groupId。 */
    private static final String DUP = ""
            + "com.a:core:jar:1.0\n"
            + "+- org.x:lib:jar:2.0:compile\n"
            + "com.b:core:jar:1.0\n"
            + "\\- org.y:other:jar:3.0:compile\n";

    @Test
    public void duplicateArtifactIdKeepsBothModules() throws Exception {
        Graph g = TreeParser.parse(new BufferedReader(new StringReader(DUP)));
        assertEquals("两个模块根", 2, g.moduleCount());
        assertEquals("模块根下标是 0 和 1", 2, g.moduleRoots().size());

        List<Module> modules = new ArrayList<Module>();
        modules.add(module("com.a", "core"));
        modules.add(module("com.b", "core"));

        List<Module> ordered = GraphJsonWriter.alignToModuleIds(g, modules);
        assertEquals("同名模块一个都不能丢", 2, ordered.size());
        assertEquals("com.a", ordered.get(0).groupId);
        assertEquals("com.b", ordered.get(1).groupId);
    }

    @Test
    public void moduleIndexMatchesModulesArrayPosition() throws Exception {
        Graph g = TreeParser.parse(new BufferedReader(new StringReader(DUP)));
        List<Module> modules = new ArrayList<Module>();
        modules.add(module("com.a", "core"));
        modules.add(module("com.b", "core"));
        List<Module> ordered = GraphJsonWriter.alignToModuleIds(g, modules);

        for (int i = 0; i < g.moduleRoots().size(); i++) {
            int mi = g.occ(g.moduleRoots().get(i)).moduleId;
            assertEquals("第 " + i + " 个模块根的下标必须与 modules 位置一致", i, mi);
            assertEquals("artifact 必须对得上",
                    g.artifactOf(g.occ(g.moduleRoots().get(i))).groupId,
                    ordered.get(mi).groupId);
        }
    }

    @Test
    public void modulesNotInRootsAreExcluded() throws Exception {
        Graph g = TreeParser.parse(new BufferedReader(new StringReader(DUP)));
        List<Module> modules = new ArrayList<Module>();
        modules.add(module("com.a", "core"));
        modules.add(module("com.b", "core"));
        modules.add(module("com.c", "ghost"));   // 树里没有
        List<Module> ordered = GraphJsonWriter.alignToModuleIds(g, modules);
        assertEquals("modules 只对应本次实际模块根", 2, ordered.size());
    }

    /**
     * 磁盘上的模块清单缺了中间一个：后面那个必须留在原位，不许前移。
     *
     * <p>这是本类最要命的那个分支 —— 前移之后 occ[].m 指向别人的 POM，
     * 而模块总数、occ 数、GA 数、omitReason 分布全都正常。
     */
    @Test
    public void missingMiddleModuleLeavesAPlaceholderSlot() throws Exception {
        String tree = ""
                + "com.a:one:jar:1.0\n"
                + "com.b:two:jar:1.0\n"
                + "com.c:three:jar:1.0\n";
        Graph g = TreeParser.parse(new BufferedReader(new StringReader(tree)));
        assertEquals(3, g.moduleCount());

        List<Module> modules = new ArrayList<Module>();
        modules.add(module("com.a", "one"));
        modules.add(module("com.c", "three"));   // com.b:two 没被发现到

        List<Module> ordered = GraphJsonWriter.alignToModuleIds(g, modules);
        assertEquals("三个根三格，缺的那个也要占位", 3, ordered.size());
        assertEquals("com.a", ordered.get(0).groupId);
        assertEquals("缺的这格坐标取图里的 artifact", "two", ordered.get(1).artifactId);
        assertEquals("占位槽没有 relPath", "", ordered.get(1).relPath);
        assertEquals("com.c 必须还在第 2 格，不许前移", "com.c", ordered.get(2).groupId);
    }

    /** 占位槽的 relPath 是空串，`/api/pom` 据此回 404 而不是猜一个路径。 */
    @Test
    public void placeholderSlotHasAnEmptyRelPath() throws Exception {
        String tree = ""
                + "com.a:one:jar:1.0\n"
                + "com.b:two:jar:1.0\n";
        Graph g = TreeParser.parse(new BufferedReader(new StringReader(tree)));
        List<Module> modules = new ArrayList<Module>();
        modules.add(module("com.a", "one"));

        String[] rels = GraphJsonWriter.relPathsOf(g, modules);
        assertEquals(2, rels.length);
        assertNotNull(rels[0]);
        assertEquals("", rels[1]);
    }

    /** 相同 GAV 重复输出时，不能把唯一的 POM 身份用于两个槽。 */
    @Test
    public void repeatedCoordinatesAreConsumedOnePerRoot() throws Exception {
        Graph g = TreeParser.parse(new BufferedReader(new StringReader(
                "com.a:core:jar:1.0\ncom.a:core:jar:1.0\n")));
        List<Module> modules = java.util.Collections.singletonList(module("com.a", "core"));
        List<Module> ordered = GraphJsonWriter.alignToModuleIds(g, modules);
        assertEquals(2, ordered.size());
        assertEquals("com.a", ordered.get(0).groupId);
        assertEquals("com.a", ordered.get(1).groupId);
        assertEquals("第二个根没有元数据", "", ordered.get(1).pomPath);
    }

    @Test
    public void actualPomIdentitySurvivesReorderAndJsonRestore() throws Exception {
        Graph g = TreeParser.parse(new BufferedReader(new StringReader(DUP)));
        List<Module> modules = new ArrayList<Module>();
        modules.add(new Module("com.b", "core", "1.0", "jar", "second", "second/custom.xml",
                0, 0, null));
        modules.add(new Module("com.a", "core", "1.0", "jar", ".", "pom.xml", 0, 0, null));
        String json = GraphJsonWriter.write(g, new GraphJsonWriter.ProjectMeta(), modules);
        org.junit.Assert.assertArrayEquals(new String[]{"pom.xml", "second/custom.xml"},
                GraphJsonWriter.pomPathsOf(g, modules));
        org.junit.Assert.assertArrayEquals(new String[]{"pom.xml", "second/custom.xml"},
                GraphJsonWriter.pomPathsOf(json));
    }

    @Test
    public void unknownPomIdentityStaysEmptyInMemoryAndJson() throws Exception {
        Graph g = TreeParser.parse(new BufferedReader(new StringReader(DUP)));
        List<Module> modules = java.util.Collections.singletonList(module("com.b", "core"));
        String json = GraphJsonWriter.write(g, new GraphJsonWriter.ProjectMeta(), modules);
        assertEquals("", GraphJsonWriter.pomPathsOf(g, modules)[0]);
        assertEquals("", GraphJsonWriter.pomPathsOf(json)[0]);
    }

    @Test
    public void sameGaDifferentVersionsFollowMavenOrder() throws Exception {
        Graph g = TreeParser.parse(new BufferedReader(new StringReader(
                "test:core:pom:2\ntest:core:pom:1\n")));
        List<Module> modules = java.util.Arrays.asList(
                new Module("test", "core", "1", "pom", "v1", "v1/pom.xml", 0, 0, null),
                new Module("test", "core", "2", "pom", "v2", "v2/pom.xml", 0, 0, null));
        List<Module> ordered = GraphJsonWriter.alignToModuleIds(g, modules);
        assertEquals("2", ordered.get(0).version);
        assertEquals("1", ordered.get(1).version);
        org.junit.Assert.assertArrayEquals(new String[]{"v2/pom.xml", "v1/pom.xml"},
                GraphJsonWriter.pomPathsOf(g, modules));
        String json = GraphJsonWriter.write(g, new GraphJsonWriter.ProjectMeta(), modules);
        org.junit.Assert.assertArrayEquals(new String[]{"v2/pom.xml", "v1/pom.xml"},
                GraphJsonWriter.pomPathsOf(json));
    }

    @Test
    public void ambiguousOrUnresolvedVersionDoesNotGuessAPom() throws Exception {
        Graph g = TreeParser.parse(new BufferedReader(new StringReader("test:core:pom:1\n")));
        Module one = new Module("test", "core", "1", "pom", "v1", "v1/pom.xml", 0, 0, null);
        Module duplicate = new Module("test", "core", "1", "pom", "other", "other/pom.xml", 0, 0, null);
        assertEquals("", GraphJsonWriter.pomPathsOf(g, java.util.Arrays.asList(one, duplicate))[0]);
        Module unknown = new Module("test", "core", "${revision}", "pom", ".", "pom.xml", 0, 0, null);
        assertEquals("", GraphJsonWriter.pomPathsOf(g, java.util.Collections.singletonList(unknown))[0]);
    }

    private static Module module(String g, String a) {
        return new Module(g, a, "1.0", "jar", ".", "pom.xml", 0, 0,
                java.util.Collections.<Module.Declared>emptyList());
    }
}
