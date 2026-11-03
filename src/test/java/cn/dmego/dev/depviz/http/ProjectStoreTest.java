package cn.dmego.dev.depviz.http;

import cn.dmego.dev.depviz.discover.ProjectInfo;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** 验证完整 JSON、键级修改以及跨实例提交不会丢掉彼此的记录。 */
public class ProjectStoreTest {
    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void missingOrCorruptFileLoadsEmpty() throws Exception {
        ProjectStore store = new ProjectStore(tmp.getRoot());
        assertTrue(store.load().isEmpty());
        Files.write(new File(tmp.getRoot(), "projects.json").toPath(),
                "{invalid".getBytes(StandardCharsets.UTF_8));
        assertTrue(store.load().isEmpty());
    }

    @Test
    public void putReplacesOnlyItsOwnKeyAndRemoveKeepsOtherKeys() {
        ProjectStore store = new ProjectStore(tmp.getRoot());
        store.put(record("/a", 1));
        store.put(record("/b", 2));
        store.put(record("/a", 3));
        List<ProjectStore.Record> back = store.load();
        assertEquals(2, back.size());
        assertEquals("/a", back.get(0).key);
        assertEquals(3, back.get(0).when);
        store.remove("/a");
        assertEquals("/b", store.load().get(0).key);
    }

    @Test
    public void invalidItemsAreSkipped() throws Exception {
        Files.write(new File(tmp.getRoot(), "projects.json").toPath(),
                "[{\"key\":\"/ok\",\"name\":\"ok\",\"moduleCount\":3,\"when\":1},{\"name\":\"no-key\"},42]"
                        .getBytes(StandardCharsets.UTF_8));
        List<ProjectStore.Record> back = new ProjectStore(tmp.getRoot()).load();
        assertEquals(1, back.size());
        assertEquals("/ok", back.get(0).key);
        assertEquals(3, back.get(0).moduleCount);
        assertNull(ProjectStore.Record.of(Collections.emptyMap()));
    }

    @Test
    public void concurrentInstancesMergeAllKeysAndLeaveNoTemporaryFiles() throws Exception {
        int writers = 8;
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        for (int i = 0; i < writers; i++) {
            final String prefix = "writer" + i;
            new Thread(() -> {
                try {
                    gate.await();
                    writeRecords(tmp.getRoot(), prefix, 12);
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                } finally {
                    done.countDown();
                }
            }).start();
        }
        gate.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS));
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        assertEquals(writers * 12, new ProjectStore(tmp.getRoot()).load().size());
        File[] leftovers = tmp.getRoot().listFiles((dir, name) -> name.endsWith(".tmp"));
        assertNotNull(leftovers);
        assertEquals(0, leftovers.length);
    }

    @Test
    public void separateJvmsMergeTransactions() throws Exception {
        List<Process> children = new ArrayList<Process>();
        try {
            for (int i = 0; i < 2; i++) {
                children.add(new ProcessBuilder(javaExecutable(), "-cp",
                        System.getProperty("java.class.path"), Writer.class.getName(),
                        tmp.getRoot().getPath(), "child" + i).inheritIO().start());
            }
            writeRecords(tmp.getRoot(), "parent", 20);
            for (Process child : children) {
                assertTrue("子 JVM 未退出", child.waitFor(20, TimeUnit.SECONDS));
                assertEquals(0, child.exitValue());
            }
            assertEquals(60, new ProjectStore(tmp.getRoot()).load().size());
        } finally {
            for (Process child : children) {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    public void staleProjectsInstanceCannotResurrectDeletedEntries() {
        ProjectStore store = new ProjectStore(tmp.getRoot());
        Projects first = new Projects(store);
        Projects second = new Projects(new ProjectStore(tmp.getRoot()));
        first.open(info("/workspace/a"));
        second.restore();
        first.remove("/workspace/a");
        second.open(info("/workspace/b"));
        List<ProjectStore.Record> back = store.load();
        assertEquals(1, back.size());
        assertEquals("/workspace/b", back.get(0).key);
    }

    private static ProjectStore.Record record(String key, long when) {
        return new ProjectStore.Record(key, new File(key).getName(), 1, when, key);
    }

    private static ProjectInfo info(String root) {
        File dir = new File(root);
        return new ProjectInfo(dir, dir, new File(dir, "pom.xml"), false, 0,
                Collections.emptyList());
    }

    private static void writeRecords(File dir, String prefix, int count) {
        for (int i = 0; i < count; i++) {
            new ProjectStore(dir).put(record("/" + prefix + "/" + i, i));
        }
    }

    private static String javaExecutable() {
        return new File(System.getProperty("java.home"), "bin/"
                + (System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java"))
                .getPath();
    }

    public static class Writer {
        public static void main(String[] args) {
            writeRecords(new File(args[0]), args[1], 20);
        }
    }
}
