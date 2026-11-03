package cn.dmego.dev.depviz.parse;

import cn.dmego.dev.depviz.model.Artifact;
import cn.dmego.dev.depviz.model.Graph;
import cn.dmego.dev.depviz.model.GraphInvariants;
import cn.dmego.dev.depviz.model.Occurrence;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 真实 demo 树保留规模回归；手写 multi 树逐项覆盖模块顺序、冲突与注解。
 *
 * <p>{@code tree/demo.txt} 来自 Maven 3.9.16 + JDK 21 的 dependency-plugin 3.6.0
 * verbose 输出。{@code tree/multi.txt} 是 19 行可人工审核的小样本，完整 JSON 见
 * {@code golden/multi.json}。
 */
public final class TreeParserTest {

    private static Graph load(String name) throws IOException {
        InputStream in = TreeParserTest.class.getResourceAsStream("/tree/" + name + ".txt");
        if (in == null) {
            throw new IllegalStateException("找不到 fixture: /tree/" + name + ".txt");
        }
        try {
            return TreeParser.parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } finally {
            in.close();
        }
    }


    /** 从一段文本直接解析，用于构造边界输入。 */
    private static Graph fromString(String text) throws IOException {
        return TreeParser.parse(new BufferedReader(new StringReader(text)));
    }

    // ---------------------------------------------------------------- demo

    @Test
    public void demoGoldenNumbers() throws IOException {
        Graph g = load("demo");
        assertEquals("总行数", 451, g.size());
        assertEquals("模块根", 1, g.moduleCount());
        assertEquals("GA 数", 208, g.artifacts().size());
        assertEquals("optReason=conflict", 36, countReason(g, "conflict"));
        assertEquals("omitReason=duplicate", 204, countReason(g, "duplicate"));
        assertEquals("被省略", 240, 451 - countEffective(g));
        assertEquals("最大深度（根取 0）", 8, g.maxDepth());
    }

    @Test
    public void demoNettyCommonFiveOccurrencesOneEffective() throws IOException {
        Graph g = load("demo");
        int total = 0;
        int eff = 0;
        for (Occurrence o : g.occurrences()) {
            Artifact a = g.artifactOf(o);
            if ("io.netty".equals(a.groupId) && "netty-common".equals(a.artifactId)) {
                total++;
                if (o.effective) {
                    eff++;
                }
            }
        }
        assertEquals("netty-common 出现次数", 5, total);
        assertEquals("netty-common 生效次数", 1, eff);
    }

    // ---------------------------------------------------------------- multi

    @Test
    public void multiGoldenNumbers() throws IOException {
        Graph g = load("multi");
        assertEquals("总行数", 19, g.size());
        assertEquals("模块根", 4, g.moduleCount());
        assertEquals("GA 数", 13, g.artifacts().size());
        assertEquals("conflict", 3, countReason(g, "conflict"));
        assertEquals("duplicate", 1, countReason(g, "duplicate"));
        assertEquals("含根生效", 15, countEffective(g));
        assertEquals("非根生效", 11, countEffective(g) - g.moduleCount());
        assertEquals("最大深度（根取 0）", 3, g.maxDepth());
    }

    @Test
    public void multiKeepsIndependentModuleWinnersAndLocalOmissions() throws IOException {
        Graph g = load("multi");
        Artifact slf4j = g.artifacts().get(1);
        assertEquals("org.slf4j:slf4j-api", slf4j.key());
        assertEquals("版本也按首次出现建索引",
                Arrays.asList("1.7.36", "1.6.0", "2.0.13"), slf4j.versions);
        List<Integer> winners = new ArrayList<Integer>();
        for (Occurrence o : g.occurrences()) {
            if (o.gaId == 1 && o.effective) {
                winners.add(o.id);
            }
        }
        assertEquals("两个模块各有 winner", Arrays.asList(1, 11), winners);
        assertEquals(0, g.occ(1).moduleId);
        assertEquals(3, g.occ(11).moduleId);
        assertEquals("1.7.36", slf4j.versions.get(g.occ(1).versionId));
        assertEquals("2.0.13", slf4j.versions.get(g.occ(11).versionId));

        Occurrence libLoser = g.occ(3);
        Occurrence appLoser = g.occ(14);
        assertFalse(libLoser.effective);
        assertFalse(appLoser.effective);
        assertEquals("conflict", libLoser.omitReason);
        assertEquals("conflict", appLoser.omitReason);
        assertEquals(0, libLoser.moduleId);
        assertEquals(3, appLoser.moduleId);
        assertEquals("1.7.36", libLoser.omittedBy);
        assertEquals("2.0.13", appLoser.omittedBy);
        assertEquals("同一 loser 在 lib 的路径更短", 2, g.depthOf(libLoser.id));
        assertEquals("app 的 loser 经两层中间依赖", 3, g.depthOf(appLoser.id));

        Occurrence duplicate = g.occ(5);
        assertFalse(duplicate.effective);
        assertEquals("duplicate", duplicate.omitReason);
        assertEquals("duplicate 与它的 winner 同模块", g.occ(1).moduleId, duplicate.moduleId);
        assertEquals(g.occ(1).versionId, duplicate.versionId);
        assertEquals("duplicate 没有 conflict version", "", duplicate.omittedBy);
    }

    @Test
    public void multiEqualDepthConflictKeepsFirstDeclaredVersion() throws IOException {
        Graph g = load("multi");
        Occurrence winner = g.occ(16);
        Occurrence loser = g.occ(18);
        assertEquals("fixture.deps:choice", g.artifactOf(winner).key());
        assertEquals(winner.gaId, loser.gaId);
        assertEquals(winner.moduleId, loser.moduleId);
        assertEquals(2, g.depthOf(winner.id));
        assertEquals(g.depthOf(winner.id), g.depthOf(loser.id));
        assertTrue("先声明的路径先出现", winner.parent < loser.parent);
        assertTrue(winner.effective);
        assertFalse(loser.effective);
        assertEquals("1.0", g.artifactOf(winner).versions.get(winner.versionId));
        assertEquals("2.0", g.artifactOf(loser).versions.get(loser.versionId));
        assertEquals("conflict", loser.omitReason);
        assertEquals("1.0", loser.omittedBy);
    }

    @Test
    public void multiKeepsManagedFieldsClassifierAndFreeTextAnnotation() throws IOException {
        Graph g = load("multi");
        assertEquals("scope not updated to", 1, countField(g, Field.SNU));
        assertEquals("version managed from", 1, countField(g, Field.VM));
        assertEquals("scope managed from", 1, countField(g, Field.SM));
        assertEquals("classifier", 1, countField(g, Field.CLASSIFIER));
        Occurrence nativeDep = g.occ(6);
        assertEquals("linux-x86_64", nativeDep.classifier);
        assertEquals("runtime", nativeDep.scope);
        assertEquals("2.5", nativeDep.versionManagedFrom);
        assertEquals("compile", nativeDep.scopeManagedFrom);
        assertEquals("test", nativeDep.scopeNotUpdatedTo);
        assertEquals("version managed from 2.5; scope managed from compile; "
                + "scope not updated to test; fixture note", nativeDep.annotation);
        assertEquals("没有注解时保留空串", "", g.occ(1).annotation);
    }

    // ------------------------------------------------------------- 下标规则

    @Test
    public void gasIndexFollowsFirstAppearanceNotAlphabetical() throws IOException {
        for (String name : new String[]{"demo", "multi"}) {
            Graph g = load(name);
            assertEquals(name + ": occ[0].ga 必须是 0", 0, g.occurrences().get(0).gaId);
            // gas[0] 必须是首行那个坐标，而不是字典序最小的那个
            Artifact first = g.artifactOf(g.occurrences().get(0));
            assertEquals(name + ": gas[0] 就是首行坐标", first, g.artifacts().get(0));
            String alphaSmallest = null;
            for (Artifact a : g.artifacts()) {
                if (alphaSmallest == null || a.key().compareTo(alphaSmallest) < 0) {
                    alphaSmallest = a.key();
                }
            }
            assertTrue(name + ": gas[0] 不应是字典序最小", !first.key().equals(alphaSmallest));
        }
    }

    @Test
    public void moduleIndexFollowsModuleRootOrder() throws IOException {
        Graph g = load("multi");
        assertEquals(Arrays.asList(0, 7, 8, 10), g.moduleRoots());
        // 磁盘发现顺序为 multi-parent/app/util/lib，图按树中的 lib/parent/util/app 编号。
        String[] names = {"lib", "multi-parent", "util", "app"};
        String[] types = {"jar", "pom", "jar", "pom"};
        for (int i = 0; i < g.moduleRoots().size(); i++) {
            Occurrence root = g.occ(g.moduleRoots().get(i));
            assertEquals("第 " + i + " 个模块根的 m", i, root.moduleId);
            assertEquals(names[i], g.artifactOf(root).artifactId);
            assertEquals(types[i], root.type);
            assertEquals(-1, root.parent);
        }
    }

    // ------------------------------------------------------------ 不变式

    @Test
    public void invariantsHoldOnBothFixtures() throws IOException {
        for (String name : new String[]{"demo", "multi"}) {
            List<GraphInvariants.Violation> v = new ArrayList<GraphInvariants.Violation>();
            GraphInvariants.check(load(name), v);
            assertTrue(name + " 不变式违反: " + v, v.isEmpty());
        }
    }

    @Test
    public void parentIndexAlwaysLessThanChildIndex() throws IOException {
        Graph g = load("multi");
        for (Occurrence o : g.occurrences()) {
            assertTrue("occ " + o.id + " 的 parent 必须更小", o.parent < o.id);
        }
    }

    // ------------------------------------------------------------ 私有工具

    private enum Field { SNU, VM, SM, CLASSIFIER }

    private static int countEffective(Graph g) {
        int n = 0;
        for (Occurrence o : g.occurrences()) {
            if (o.effective) {
                n++;
            }
        }
        return n;
    }

    private static int countReason(Graph g, String reason) {
        int n = 0;
        for (Occurrence o : g.occurrences()) {
            if (reason.equals(o.omitReason)) {
                n++;
            }
        }
        return n;
    }

    private static int countField(Graph g, Field f) {
        int n = 0;
        for (Occurrence o : g.occurrences()) {
            String v;
            switch (f) {
                case SNU: v = o.scopeNotUpdatedTo; break;
                case VM: v = o.versionManagedFrom; break;
                case SM: v = o.scopeManagedFrom; break;
                default: v = o.classifier; break;
            }
            if (v != null && !v.isEmpty()) {
                n++;
            }
        }
        return n;
    }

    /**
     * 5 段坐标只有两种合法来源：g:a:type:version:scope（末段是已知 scope），
     * 以及 g:a:type:classifier:version（classifier 有、scope 为空）。
     *
     * <p>早先的实现只认第一种，末段不是已知 scope 就整棵树解析失败。真实数据打破了这个
     * 假设：{@code com.github.dadiyang:equator:1.0.4} 的 POM 写的是
     * {@code <scope>tests</scope>}（多个 s），Maven 不校验、原样输出，于是 dynamic-tp
     * 这类下游项目整份扫描都失败在一个第三方 POM 的错别字上。
     *
     * <p>现在按「哪一位像版本号」分三种：第 4 段像、第 5 段不像 → 上游写了野 scope，
     * 照收；第 4 段不像、第 5 段像 → 是带 classifier 的坐标；两位都像/都不像 → 抛。
     */
    @Test
    public void nonStandardScopeIsToleratedInsteadOfFailingTheWholeTree() throws Exception {
        // equator-1.0.4 的真实形态：test 多写了一个 s
        Graph g = fromString("+- org.hamcrest:hamcrest-core:jar:2.2:tests\n");
        assertEquals("原样保留上游写的词，不替它圆成 test",
                "tests", g.occurrences().get(0).scope);
        assertEquals("2.2", g.artifacts().get(0).versions.get(0));
        assertEquals("", g.occurrences().get(0).classifier);
    }

    @Test
    public void fivePartWithoutKnownScopeFallsBackToClassifier() throws Exception {
        // 第 4 段不像版本（linux-x86_64 虽含 86/64，但它在这里是 classifier 位，
        // 而第 5 段 1.2.3 明确像版本）→ 认作 classifier:version
        Graph g = fromString("+- com.example:demo:jar:linux-x86_64:1.2.3\n");
        assertEquals("linux-x86_64", g.occurrences().get(0).classifier);
        assertEquals("1.2.3", g.artifacts().get(0).versions.get(0));
    }

    @Test
    public void genuinelyAmbiguousFivePartStillFails() throws Exception {
        // 两位都像版本：分不清谁是版本，宁可失败也不猜
        String bad = "+- com.example:demo:jar:1.0:2.0\n";
        try {
            fromString(bad);
            fail("两段都像版本号，应当解析失败");
        } catch (ParseException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("5 段坐标无法判定"));
        }
    }

    /** 合法的 5 段（末段是 scope）与 6 段（带 classifier）都必须照常通过。 */
    @Test
    public void legalFiveAndSixPartCoordinatesStillParse() throws Exception {
        Graph five = fromString("+- com.example:demo:jar:1.2.3:compile\n");
        assertEquals("compile", five.occurrences().get(0).scope);
        assertEquals("", five.occurrences().get(0).classifier);
        Graph six = fromString("+- com.example:demo:jar:linux-x86_64:1.2.3:compile\n");
        assertEquals("linux-x86_64", six.occurrences().get(0).classifier);
        assertEquals("compile", six.occurrences().get(0).scope);
    }

    /**
     * {@code <tokens>extended</tokens>} 输出的是 Unicode 制表符，必须照常解析。
     *
     * <p>项目在自己的 pom 里给 dependency-plugin 配了 {@code extended} 时，Maven 一定按
     * 项目配置输出，命令行再加 {@code -Dtokens=standard} 也覆盖不掉。旧正则只认 ASCII
     * 连接符，{@code "└─ junit:junit:jar:4.13.2:compile"} 整行落进坐标位：groupId 变成
     * {@code "└─ junit"}、深度算成 0、被当成一个新的模块根 —— 单模块工程扫出两个模块，
     * 而 {@code ok=true}，没有任何报错。
     */
    @Test
    public void extendedTokensAreParsedAsRealDependenciesNotFakeRoots() throws Exception {
        String tree = "com.example:root:jar:1.0:compile\n"
                + "└─ junit:junit:jar:4.13.2:compile\n"
                + "   └─ org.hamcrest:hamcrest-core:jar:1.3:compile\n"
                + "├─ com.example:lib:jar:2.0:compile\n"
                + "│  └─ com.example:deep:jar:3.0:compile\n";
        Graph g = fromString(tree);
        assertEquals("整棵树只有一个模块根", 1, g.moduleCount());
        assertEquals("总行数", 5, g.size());
        assertEquals("GA 数", 5, g.artifacts().size());
        assertEquals("根取 0 的最大深度", 2, g.maxDepth());
        // 假 GA 的典型症状：groupId 里混进装饰字符
        for (Artifact a : g.artifacts()) {
            assertTrue("坐标里混进了装饰字符: " + a.key(),
                    a.key().indexOf('└') < 0 && a.key().indexOf('├') < 0
                            && a.key().indexOf('│') < 0 && a.key().indexOf('─') < 0);
        }
        assertEquals("junit 的父必须是根", 0, g.occurrences().get(1).parent);
        assertEquals("hamcrest 的父必须是 junit", 1, g.occurrences().get(2).parent);
        assertEquals("lib 的父必须是根", 0, g.occurrences().get(3).parent);
        assertEquals("deep 的父必须是 lib", 3, g.occurrences().get(4).parent);
    }
}
