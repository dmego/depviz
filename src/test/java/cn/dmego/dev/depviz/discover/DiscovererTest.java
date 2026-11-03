package cn.dmego.dev.depviz.discover;

import cn.dmego.dev.depviz.model.Module;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** 项目发现测试使用仓库内 POM 和临时项目，所有用例在任意机器上执行。 */
public final class DiscovererTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    /** 仓库内四个 POM：{@code multi} → {@code app} → {@code app/util}，再加 {@code lib}。 */
    private static final File MULTI = new File("src/test/resources/projects/multi");
    private static final File DEMO = new File("src/test/resources/projects/demo");

    @Test
    public void enteringFromLibRelocatesToOuterAggregator() throws Exception {
        ProjectInfo info = new Discoverer().discover(new File(MULTI, "lib"));
        assertEquals("模块数", 4, info.moduleCount());
        assertTrue("从子模块进入必须标记 relocated", info.relocated);
        assertEquals(MULTI.getCanonicalPath(), info.rootDir);
        assertEquals("声明依赖总数", 1, info.declaredDependencyCount());
    }

    @Test
    public void multiModuleRelPathsAndPomPathsAreRelative() throws Exception {
        ProjectInfo info = new Discoverer().discover(MULTI);
        assertEquals(java.util.Arrays.asList(".", "app", "app/util", "lib"), relPaths(info));
        String[] pomPaths = {"pom.xml", "app/pom.xml", "app/util/pom.xml", "lib/pom.xml"};
        int[] childCounts = {2, 1, 0, 0};
        for (int i = 0; i < info.modules.size(); i++) {
            Module module = info.modules.get(i);
            assertFalse(new File(module.relPath).isAbsolute());
            assertEquals(pomPaths[i], module.pomPath);
            assertEquals(childCounts[i], module.childCount);
        }
    }

    @Test
    public void optionalKeepsRawString() throws Exception {
        File dir = tmp.newFolder("optional");
        writePom(dir, "optional", "<dependencies>"
                + "<dependency><groupId>t</groupId><artifactId>required</artifactId>"
                + "<version>1</version></dependency>"
                + "<dependency><groupId>t</groupId><artifactId>optional</artifactId>"
                + "<version>2</version><scope>runtime</scope><optional>true</optional></dependency>"
                + "</dependencies>");
        ProjectInfo info = new Discoverer().discover(dir);
        Module module = info.modules.get(0);
        assertEquals(2, info.declaredDependencyCount());
        assertEquals("", module.declared.get(0).optional);
        assertEquals("", module.declared.get(0).scope);
        assertEquals("true", module.declared.get(1).optional);
        assertEquals("runtime", module.declared.get(1).scope);
    }

    @Test
    public void demoIsSingleModule() throws Exception {
        assertTrue("仓库内 demo POM 必须存在", new File(DEMO, "pom.xml").isFile());
        ProjectInfo info = new Discoverer().discover(DEMO);
        assertEquals(1, info.moduleCount());
        assertTrue("从根进入不 relocate", !info.relocated);
    }

    /** 三层嵌套 fixture：{@code multi} → {@code app} → {@code app/util}，再加上 {@code lib}。 */
    @Test
    public void multiModuleFixtureFindsThreeLevels() throws Exception {
        ProjectInfo info = new Discoverer().discover(MULTI);

        assertEquals("模块数", 4, info.moduleCount());
        assertFalse("从根进入不 relocate", info.relocated);
        assertEquals("根模块 relPath", ".", info.modules.get(0).relPath);
        assertTrue("存在嵌套子模块的 relPath", relPaths(info).contains("app/util"));
        assertTrue("存在二级模块的 relPath", relPaths(info).contains("app"));
    }

    /**
     * 从最深的子模块进入，向上找到的是<b>最近的 aggregator</b>，不是最外层的那个。
     *
     * <p>{@code app/util} 往上第一个声明了 {@code <modules>} 的是 {@code app}，所以根是 {@code app}
     * 而不是 {@code multi}。这是 Maven 的 reactor 语义（在 {@code app/} 里跑 {@code mvn} 就是只构建
     * {@code app} 这一支），也是「最近的 aggregator 优先」这条规则直接推出来的结果。
     * 我第一版把期望写成 {@code multi}、4 个模块 —— 那是把「往上走到底」和「找到 reactor 根」混为一谈。
     */
    @Test
    public void enteringFromNestedSubModuleRelocatesToNearestAggregator() throws Exception {
        ProjectInfo info = new Discoverer().discover(new File(MULTI, "app/util"));

        assertEquals("最近 aggregator 的模块数", 2, info.moduleCount());
        assertTrue("从子模块进入必须标记 relocated", info.relocated);
        assertEquals("根目录", new File(MULTI, "app").getAbsoluteFile().getAbsolutePath(), info.rootDir);
        assertEquals("selectedDir 是用户选的那个", "app/util",
                MULTI.getAbsoluteFile().toPath().relativize(
                        new File(info.selectedDir).toPath()).toString().replace('\\', '/'));
    }

    /** {@code declared[]} 逐条保留原始字符串，{@code optional} 只有 "" 与 "true" 两种取值。 */
    @Test
    public void declaredDependenciesKeepRawOptionalString() throws Exception {
        ProjectInfo info = new Discoverer().discover(MULTI);

        Module lib = null;
        for (Module m : info.modules) {
            if ("lib".equals(m.artifactId)) {
                lib = m;
            }
        }
        assertNotNull("fixture 里应该有 lib 模块", lib);
        assertEquals("lib 声明了 1 个依赖", 1, lib.declaredDeps);
        assertEquals("groupId", "org.slf4j", lib.declared.get(0).groupId);
        assertEquals("artifactId", "slf4j-api", lib.declared.get(0).artifactId);
        assertEquals("version", "1.7.36", lib.declared.get(0).version);
        assertEquals("optional 缺省是空串而不是 null", "", lib.declared.get(0).optional);
        assertEquals("整个 fixture 的声明依赖数", 1, info.declaredDependencyCount());
    }

    private static java.util.List<String> relPaths(ProjectInfo info) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (Module m : info.modules) {
            out.add(m.relPath);
        }
        return out;
    }

    /** 模块互相引用（a → ../b → ../a）时不能无限展开成重复模块。 */
    @Test
    public void moduleCycleDoesNotDuplicate() throws Exception {
        java.io.File dir = tmp.newFolder("cyc");
        writePom(dir, "root", "<modules><module>a</module></modules>");
        java.io.File a = new java.io.File(dir, "a");
        java.io.File b = new java.io.File(dir, "b");
        a.mkdirs();
        b.mkdirs();
        writePom(a, "a", "<modules><module>../b</module></modules>");
        writePom(b, "b", "<modules><module>../a</module></modules>");

        cn.dmego.dev.depviz.discover.ProjectFinder.Found found =
                new cn.dmego.dev.depviz.discover.ProjectFinder().find(dir);
        assertEquals("循环引用只应展开 3 个模块（root/a/b）", 3, found.moduleCount());
        assertFalse("relPath 里不应残留 ..", found.relPaths.toString().contains(".."));
    }

    private static void writePom(java.io.File dir, String artifactId, String modules)
            throws java.io.IOException {
        String xml = "<?xml version=\"1.0\"?>\n"
                + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
                + "<modelVersion>4.0.0</modelVersion>"
                + "<groupId>t</groupId><artifactId>" + artifactId + "</artifactId>"
                + "<version>1</version><packaging>pom</packaging>"
                + modules + "</project>";
        java.nio.file.Files.write(new java.io.File(dir, "pom.xml").toPath(),
                xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * 没有 aggregator 在上层时，用<b>最近的 pom</b>，不是最外层的。
     *
     * <p>早先 {@code locateRootPom} 无条件 {@code best = candidate}，会一路覆盖到最外层，
     * 把一个嵌在别的 Maven 项目里的独立项目认成外层项目的子模块。这个用例造出
     * 「外层有 pom 但不是 aggregator」的场景来钉住「最近优先」。
     *
     * <p>注意：外层<b>是</b> aggregator 且声明了这个子目录时，用它当根是<b>正确</b>的
     * —— 那是 Maven 的 reactor 语义（从子模块进入也应该扫整个 reactor），
     * 见 {@link #enteringFromLibRelocatesToOuterAggregator()}。
     */
    @Test
    public void nearestPomWinsWhenNoAggregatorAbove() throws Exception {
        File outer = tmp.newFolder("outer");
        writePom(outer, "outer", "");          // 外层只是个普通 pom，不声明 modules
        File inner = new File(outer, "inner");
        inner.mkdirs();
        writePom(inner, "inner", "");

        ProjectFinder.Found found = new ProjectFinder().find(inner);

        assertEquals("必须用最近的 pom 当根", inner.getCanonicalFile(),
                found.rootPom.getParentFile().getCanonicalFile());
        assertEquals("独立项目只有一个模块", 1, found.moduleCount());
    }

    /**
     * 根 pom 不是合法 XML 时必须**报错**，不能当成「没有子模块的单模块项目」。
     *
     * <p>实测缺一个闭合标签的 pom 曾让 {@code /api/discover} 返回
     * {@code moduleCount: 1}，随后 Maven 扫描才失败 —— 用户看到「发现成功但扫描失败」，
     * 根因被藏起来。
     */
    @Test
    public void brokenRootPomIsRejected() throws Exception {
        File dir = tmp.newFolder("broken");
        writeRaw(new File(dir, "pom.xml"),
                "<project><modules><module>a</modules></project>");
        try {
            new Discoverer().discover(dir);
            fail("根 pom 语法错误必须抛异常");
        } catch (DiscoverException e) {
            assertTrue("错误信息要说清是 XML 不合法: " + e.getMessage(),
                    e.getMessage().contains("不是合法 XML"));
        }
    }

    /**
     * 外层有 {@code <modules>}，但它声明的不是我们选的那个目录 → 不能往上提。
     *
     * <p>实测形态：{@code outer/pom.xml} 只声明 {@code included}，旁边放着一个
     * {@code standalone}（自己的 pom、没有 parent、也没被任何 {@code <modules>} 声明）。
     * 旧逻辑看到外层有 {@code <modules>} 就把根提到 outer，用户选 {@code standalone}
     * 却扫出 {@code included} 和 {@code outer}，所选项目完全消失。
     */
    @Test
    public void unrelatedSiblingIsNotSwallowedByAncestorAggregator() throws Exception {
        File outer = tmp.newFolder("outer2");
        writePom(outer, "outer", "<modules><module>included</module></modules>");
        File included = new File(outer, "included");
        assertTrue(included.mkdirs());
        writePom(included, "included", "");
        File standalone = new File(outer, "standalone");
        assertTrue(standalone.mkdirs());
        writePom(standalone, "standalone", "");

        ProjectFinder.Found found = new ProjectFinder().find(standalone);

        assertEquals("必须把 standalone 自己当根", standalone.getCanonicalFile(),
                found.rootPom.getParentFile().getCanonicalFile());
        assertEquals("独立项目只有一个模块", 1, found.moduleCount());
    }

    /**
     * 声明为 {@code <module>x/custom.xml</module>} 的模块也要被认作「包含在内」。
     *
     * <p>{@code <module>} 的文本是<b>相对 aggregator 目录的 pom 路径</b>，
     * 不一定就叫 {@code pom.xml}。
     */
    @Test
    public void moduleDeclaredByExplicitPomPathCounts() throws Exception {
        File outer = tmp.newFolder("outer3");
        writePom(outer, "outer", "<modules><module>poms/app.xml</module></modules>");
        File poms = new File(outer, "poms");
        assertTrue(poms.mkdirs());
        writeRaw(new File(poms, "app.xml"),
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
                        + "<modelVersion>4.0.0</modelVersion>"
                        + "<groupId>t</groupId><artifactId>app</artifactId>"
                        + "<version>1</version></project>");
        ProjectInfo info = new Discoverer().discover(poms);

        assertEquals(outer.getCanonicalPath(), new File(info.rootDir).getCanonicalPath());
        assertEquals("poms", info.modules.get(1).relPath);
        assertEquals("poms/app.xml", info.modules.get(1).pomPath);
    }

    /**
     * {@code activeByDefault} 的 profile 里声明的模块也算模块。
     *
     * <p>Maven 默认激活它们，树里就有这些模块；我们少收一个，{@code modules[]} 就会
     * 相对 {@code occ[].m} 整体前移（见 {@code GraphJsonWriter.alignToModuleIds}）。
     */
    @Test
    public void modulesInActiveByDefaultProfileAreCollected() throws Exception {
        File dir = tmp.newFolder("profiled");
        writeRaw(new File(dir, "pom.xml"),
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
                        + "<modelVersion>4.0.0</modelVersion>"
                        + "<groupId>t</groupId><artifactId>root</artifactId><version>1</version>"
                        + "<packaging>pom</packaging>"
                        + "<profiles><profile><id>extra</id>"
                        + "<activation><activeByDefault>true</activeByDefault></activation>"
                        + "<modules><module>inner</module></modules>"
                        + "</profile></profiles></project>");
        File inner = new File(dir, "inner");
        assertTrue(inner.mkdirs());
        writePom(inner, "inner", "");

        ProjectInfo info = new Discoverer().discover(dir);

        assertEquals("根 + profile 里的模块", 2, info.moduleCount());
        assertEquals("inner", info.modules.get(1).relPath);
    }

    /** 没被激活的 profile 里的模块不能收（Maven 默认不会构建它们）。 */
    @Test
    public void modulesInInactiveProfileAreIgnored() throws Exception {
        File dir = tmp.newFolder("profiled-off");
        writeRaw(new File(dir, "pom.xml"),
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
                        + "<modelVersion>4.0.0</modelVersion>"
                        + "<groupId>t</groupId><artifactId>root</artifactId><version>1</version>"
                        + "<packaging>pom</packaging>"
                        + "<profiles><profile><id>extra</id>"
                        + "<activation><activeByDefault>false</activeByDefault></activation>"
                        + "<modules><module>inner</module></modules>"
                        + "</profile></profiles></project>");
        File inner = new File(dir, "inner");
        assertTrue(inner.mkdirs());
        writePom(inner, "inner", "");

        ProjectInfo info = new Discoverer().discover(dir);

        assertEquals("只有根", 1, info.moduleCount());
    }

    /** 子模块的 pom 坏了不该让整个发现失败（它会被收进列表，Maven 扫描时给精确报错）。 */
    @Test
    public void brokenChildPomDoesNotAbortDiscovery() throws Exception {
        File dir = tmp.newFolder("brokenchild");
        writePom(dir, "root", "<modules><module>a</module></modules>");
        File a = new File(dir, "a");
        assertTrue(a.mkdirs());
        writeRaw(new File(a, "pom.xml"), "<project><modules><module>b</modules></project>");
        ProjectInfo info = new Discoverer().discover(dir);
        assertEquals("根 + 坏子模块都要被收进来", 2, info.moduleCount());
    }

    @Test
    public void independentPomInsideDeclaredModuleIsNotSwallowed() throws Exception {
        File outer = tmp.newFolder("outer-nested");
        writePom(outer, "outer", "<modules><module>included</module></modules>");
        File included = new File(outer, "included");
        assertTrue(included.mkdirs());
        writePom(included, "included", "");
        File standalone = new File(included, "nested");
        assertTrue(standalone.mkdirs());
        writePom(standalone, "standalone", "");
        ProjectInfo info = new Discoverer().discover(standalone);
        assertEquals(standalone.getCanonicalPath(), new File(info.rootDir).getCanonicalPath());
        assertEquals("standalone", info.modules.get(0).artifactId);
    }

    @Test
    public void explicitProfilesReplaceDefaultsAndSupportDeactivation() throws Exception {
        File root = tmp.newFolder("profile-selection");
        writePom(root, "root", "<profiles>"
                + "<profile><id>default</id><activation><activeByDefault>true</activeByDefault></activation>"
                + "<modules><module>defaults</module></modules></profile>"
                + "<profile><id>extra</id><modules><module>enabled</module></modules></profile></profiles>");
        for (String name : new String[]{"defaults", "enabled"}) {
            File dir = new File(root, name);
            assertTrue(dir.mkdirs());
            writePom(dir, name, "");
        }
        Discoverer discoverer = new Discoverer();
        assertEquals("defaults", discoverer.discover(root).modules.get(1).artifactId);
        ProjectInfo selected = discoverer.discover(root, java.util.Arrays.asList("extra"));
        assertEquals(2, selected.moduleCount());
        assertEquals("enabled", selected.modules.get(1).artifactId);
        assertEquals(1, discoverer.discover(root,
                java.util.Arrays.asList("!default,-extra")).moduleCount());
        assertEquals(root.getCanonicalPath(), new File(discoverer.discover(
                new File(root, "enabled"), java.util.Arrays.asList("extra")).rootDir).getCanonicalPath());
    }

    @Test
    public void inheritedPropertiesResolveModuleGaAndModulePath() throws Exception {
        File root = tmp.newFolder("property-identities");
        writeRaw(new File(root, "pom.xml"), "<project><modelVersion>4.0.0</modelVersion>"
                + "<groupId>${shared.group}</groupId><artifactId>parent</artifactId><version>1</version>"
                + "<packaging>pom</packaging><properties><shared.group>resolved.group</shared.group>"
                + "<module.dir>child</module.dir><child.name>resolved-child</child.name></properties>"
                + "<modules><module>${module.dir}/custom.xml</module></modules></project>");
        File child = new File(root, "child");
        assertTrue(child.mkdirs());
        writeRaw(new File(child, "custom.xml"), "<project><modelVersion>4.0.0</modelVersion>"
                + "<parent><groupId>resolved.group</groupId><artifactId>parent</artifactId>"
                + "<version>1</version></parent><artifactId>${child.name}</artifactId></project>");
        ProjectInfo info = new Discoverer().discover(root);
        assertEquals(2, info.moduleCount());
        assertEquals("resolved.group", info.modules.get(0).groupId);
        assertEquals("resolved.group", info.modules.get(1).groupId);
        assertEquals("resolved-child", info.modules.get(1).artifactId);
        assertEquals("child/custom.xml", info.modules.get(1).pomPath);
        assertEquals("1", info.modules.get(1).version);
    }

    @Test
    public void effectiveVersionsDistinguishModulesWithTheSameGa() throws Exception {
        File root = tmp.newFolder("same-ga-versions");
        writeRaw(new File(root, "pom.xml"), "<project><modelVersion>4.0.0</modelVersion>"
                + "<groupId>t</groupId><artifactId>parent</artifactId><version>${revision}</version>"
                + "<packaging>pom</packaging><properties><revision>2</revision></properties>"
                + "<modules><module>v1</module><module>v2</module></modules></project>");
        for (String dir : new String[]{"v1", "v2"}) {
            File module = new File(root, dir);
            assertTrue(module.mkdirs());
            writeRaw(new File(module, "pom.xml"), "<project><modelVersion>4.0.0</modelVersion>"
                    + "<parent><groupId>t</groupId><artifactId>parent</artifactId>"
                    + "<version>${revision}</version></parent><artifactId>core</artifactId>"
                    + ("v1".equals(dir) ? "<version>1</version>" : "") + "</project>");
        }
        ProjectInfo info = new Discoverer().discover(root);
        assertEquals("2", info.modules.get(0).version);
        assertEquals("1", info.modules.get(1).version);
        assertEquals("2", info.modules.get(2).version);
    }

    private static void writeRaw(File f, String content) throws java.io.IOException {
        java.nio.file.Files.write(f.toPath(), content.getBytes("UTF-8"));
    }
}
