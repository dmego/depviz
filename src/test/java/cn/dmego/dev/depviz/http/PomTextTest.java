package cn.dmego.dev.depviz.http;

import cn.dmego.dev.depviz.discover.ProjectInfo;
import cn.dmego.dev.depviz.json.GraphJsonWriter;
import cn.dmego.dev.depviz.model.Graph;
import cn.dmego.dev.depviz.model.Module;
import cn.dmego.dev.depviz.parse.TreeParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** 发现顺序、图下标和实际 POM 文件名的端到端关联。 */
public class PomTextTest {
    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void moduleIdentitySurvivesReorderingCustomPomAndRestart() throws Exception {
        File root = tmp.newFolder("reactor");
        write(root, "pom.xml", "ROOT");
        write(root, "app/pom.xml", "APP");
        write(root, "lib/custom.xml", "LIB-CUSTOM");
        List<Module> modules = Arrays.asList(module("root", ".", "pom.xml"),
                module("app", "app", "app/pom.xml"),
                module("lib", "lib", "lib/custom.xml"));
        ProjectInfo info = new ProjectInfo(root, root, new File(root, "pom.xml"), false, 0, modules);
        Graph graph = TreeParser.parse(new java.io.BufferedReader(new StringReader(
                "test:lib:jar:1\ntest:app:jar:1\ntest:root:pom:1\n")));
        File data = tmp.newFolder("data");
        ProjectStore store = new ProjectStore(data);
        GraphStore graphs = new GraphStore(data);
        Projects projects = new Projects(store, graphs);
        ProjectState state = projects.open(info);
        projects.storeGraph(state, graph, info, new GraphJsonWriter.ProjectMeta());
        assertPoms(state);
        Projects restarted = new Projects(new ProjectStore(data), new GraphStore(data));
        restarted.restore();
        assertPoms(restarted.project(root.getPath()));
    }

    @Test
    public void missingIdentityAndPathsOutsideTheProjectAreNotGuessed() throws Exception {
        File root = tmp.newFolder("safe");
        write(root, "pom.xml", "ROOT");
        write(tmp.getRoot(), "outside.xml", "OUTSIDE");
        ProjectState state = new ProjectState(root.getPath(), "safe", 0, 2,
                new ProjectState.Payload("{}", new byte[0], null,
                        new String[]{".", ".."}, new String[]{"", "../outside.xml"}));
        assertNull(PomText.read(state, 0));
        assertNull(PomText.read(state, 1));
        assertNull(PomText.read(state, -1));
        assertNull(PomText.read(state, 2));
    }

    private void assertPoms(ProjectState state) throws Exception {
        assertNotNull(state);
        assertEquals("LIB-CUSTOM", PomText.read(state, 0).text);
        assertEquals("APP", PomText.read(state, 1).text);
        assertEquals("ROOT", PomText.read(state, 2).text);
        assertTrue(PomText.read(state, 0).pomPath.endsWith("lib/custom.xml"));
    }

    private static Module module(String artifact, String rel, String pom) {
        return new Module("test", artifact, "1", "jar", rel, pom, 0, 0, Collections.emptyList());
    }

    private static void write(File root, String path, String text) throws Exception {
        File file = new File(root, path);
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }
}
