package cn.dmego.dev.depviz.http;

import cn.dmego.dev.depviz.json.GraphJsonWriter;
import cn.dmego.dev.depviz.model.Graph;
import cn.dmego.dev.depviz.parse.TreeParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 扫描产物落盘、目录浏览接口、配置字段类型三个新行为。
 *
 * <p>为什么不并进 {@link RouterScanTest}：那个类管扫描状态机，这里管「扫完之后磁盘上
 * 留下了什么」与「选目录这条路还通不通」，混在一起会超过 300 行。
 */
public class GraphStoreTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    // ---------------------------------------------------------- 落盘与读回

    /** 扫描产物要落在 {@code <dataDir>/<项目名>/graph.json}，而不是只留在内存里。 */
    @Test
    public void graphIsSavedUnderProjectName() throws Exception {
        File dataDir = tmp.newFolder("data");
        GraphStore store = new GraphStore(dataDir);
        File saved = store.save("async-mirror", "/some/where/async-mirror", "{\"occ\":[]}");

        assertNotNull("应该写出文件", saved);
        assertEquals(new File(new File(dataDir, "async-mirror"), "graph.json").getAbsoluteFile(),
                saved.getAbsoluteFile());
        assertEquals("{\"occ\":[]}",
                new String(Files.readAllBytes(saved.toPath()), StandardCharsets.UTF_8));
    }

    /** 同名不同路径的两个项目不能互相覆盖 —— 后一个退到 {@code <名字>-<hash8>}。 */
    @Test
    public void sameNameDifferentProjectsDoNotCollide() throws Exception {
        File dataDir = tmp.newFolder("data");
        GraphStore store = new GraphStore(dataDir);
        File a = store.save("demo", "/home/alice/demo", "{\"who\":\"alice\"}");
        File b = store.save("demo", "/home/bob/demo", "{\"who\":\"bob\"}");

        assertNotNull(a);
        assertNotNull(b);
        assertFalse("两个项目不能写到同一个文件", a.getAbsolutePath().equals(b.getAbsolutePath()));
        assertEquals("{\"who\":\"alice\"}", store.load("demo", "/home/alice/demo"));
        assertEquals("{\"who\":\"bob\"}", store.load("demo", "/home/bob/demo"));
    }

    /** 同一个项目重扫必须覆盖自己那份，而不是每次生成新目录。 */
    @Test
    public void rescanOverwritesSameFile() throws Exception {
        File dataDir = tmp.newFolder("data");
        GraphStore store = new GraphStore(dataDir);
        store.save("demo", "/a/demo", "{\"v\":1}");
        store.save("demo", "/a/demo", "{\"v\":2}");
        assertEquals("{\"v\":2}", store.load("demo", "/a/demo"));
        assertEquals("重扫不应产生第二个目录", 1,
                new File(dataDir, "demo").getParentFile().listFiles().length);
    }

    /** 项目名里的斜杠、空白不能变成路径分隔符。 */
    @Test
    public void projectNameIsSanitized() throws Exception {
        File dataDir = tmp.newFolder("data");
        GraphStore store = new GraphStore(dataDir);
        File saved = store.save("a/b c", "/x/a", "{}");
        assertNotNull(saved);
        assertTrue(saved.getParentFile().getName().startsWith("a_b_c"));
        assertEquals("{}", store.load("a/b c", "/x/a"));
    }

    /** 重启后 {@link Projects#restore()} 要把图读回来，而不是只恢复「最近打开」的元数据。 */
    @Test
    public void restoreLoadsGraphFromDisk() throws Exception {
        File dataDir = tmp.newFolder("data");
        GraphStore gs = new GraphStore(dataDir);
        gs.save("demo", "/p/demo", "{\"occ\":[1,2,3],\"gas\":[]}");

        ProjectStore ps = new ProjectStore(dataDir);
        ps.put(new ProjectStore.Record("/p/demo", "demo", 1, 1234L, "/p/demo"));

        Projects projects = new Projects(ps, gs);
        projects.restore();

        ProjectState p = projects.project("/p/demo");
        assertNotNull("记录应该被恢复", p);
        assertNotNull("图应该从磁盘读回来", p.payload);
        assertTrue(p.payload.json.contains("\"occ\":[1,2,3]"));
    }

    /** 没有落盘文件时 restore 不能炸，只是拿不到图。 */
    @Test
    public void restoreWithoutGraphFileKeepsNullPayload() throws Exception {
        File dataDir = tmp.newFolder("data");
        ProjectStore ps = new ProjectStore(dataDir);
        ps.put(new ProjectStore.Record("/p/none", "none", 2, 1L, "/p/none"));

        Projects projects = new Projects(ps, new GraphStore(dataDir));
        projects.restore();
        ProjectState p = projects.project("/p/none");
        assertNotNull(p);
        assertNull("没有图文件时 payload 应该是 null", p.payload);
    }

    /**
     * 重启恢复出来的项目要能直接打开 POM 弹窗 —— 模块编号 → 目录的映射
     * 必须从持久化的 {@code modules[].relPath} 读回来。
     *
     * <p>不能「重新 discover 一遍」：发现顺序是磁盘顺序，而 {@code occ[].m} 是
     * 模块根在 Maven 树里的顺序，两者不同源（见 {@link GraphJsonWriter#alignToModuleIds}）。
     */
    @Test
    public void restoreRecoversRelPathsFromTheSavedGraph() throws Exception {
        File dataDir = tmp.newFolder("data");
        String key = new File("src/test/resources/projects/demo").getAbsolutePath();
        GraphStore gs = new GraphStore(dataDir);
        gs.save("demo", key, "{\"modules\":[{\"relPath\":\".\",\"pomPath\":\"pom.xml\"}]}");

        ProjectStore ps = new ProjectStore(dataDir);
        ps.put(new ProjectStore.Record(key, "demo", 1, 1L, key));

        Projects projects = new Projects(ps, gs);
        projects.restore();
        ProjectState p = projects.project(key);
        assertNotNull(p);
        assertArrayEquals(new String[]{"."}, p.payload.relPaths);

        PomText pom = PomText.read(p, 0);
        assertNotNull("模块 0 的 POM 应该能读出来", pom);
        assertTrue(pom.text.contains("<project"));
        assertNull("越界的模块编号回 null（路由层转成 404）", PomText.read(p, 7));
    }

    /** 图文件坏了：relPaths 退回空数组，POM 弹窗回 404，但图本身还能发出去。 */
    @Test
    public void brokenGraphFileYieldsNoRelPaths() throws Exception {
        File dataDir = tmp.newFolder("data");
        GraphStore gs = new GraphStore(dataDir);
        gs.save("bad", "/p/bad", "{\"modules\":[oops}");

        ProjectStore ps = new ProjectStore(dataDir);
        ps.put(new ProjectStore.Record("/p/bad", "bad", 1, 1L, "/p/bad"));

        Projects projects = new Projects(ps, gs);
        projects.restore();
        ProjectState p = projects.project("/p/bad");
        assertNotNull(p.payload);
        assertEquals(0, p.payload.relPaths.length);
        assertNull(PomText.read(p, 0));
    }

    // ---------------------------------------------------------- 解析产物

    /** 用 fixture 走一遍「解析 → 落盘 → 读回」，确认内容没被改动。 */
    @Test
    public void savedRealGraphRoundTrips() throws Exception {
        Graph graph = TreeParser.parse(new java.io.BufferedReader(new java.io.InputStreamReader(
                new java.io.FileInputStream("src/test/resources/tree/demo.txt"),
                StandardCharsets.UTF_8)));
        GraphJsonWriter.ProjectMeta meta = new GraphJsonWriter.ProjectMeta();
        meta.id = "/x/demo";
        meta.name = "demo";
        meta.rootDir = "/x/demo";
        String json = GraphJsonWriter.write(graph, meta, Collections.emptyList());

        File dataDir = tmp.newFolder("data");
        GraphStore store = new GraphStore(dataDir);
        assertNotNull(store.save("demo", "/x/demo", json));
        assertEquals(json, store.load("demo", "/x/demo"));
    }

}
