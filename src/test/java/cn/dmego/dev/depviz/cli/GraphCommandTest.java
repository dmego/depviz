package cn.dmego.dev.depviz.cli;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@code graph <graph.json> --stats} —— 对已有产物出统计。
 *
 * <p>排查时手上通常只有上次扫出来的 json：没有原始 tree.txt，也没有项目目录。
 * 这条路径不跑 Maven、不碰项目目录，只数 occ 数组。
 */
public class GraphCommandTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    /** 手写一个小图，字段名与后端产物一致（紧凑键 i/p/ga/v/e/m/t/o）。 */
    private File writeGraph(int occCount, int roots) throws Exception {
        StringBuilder occ = new StringBuilder();
        for (int i = 0; i < occCount; i++) {
            if (i > 0) {
                occ.append(',');
            }
            boolean isRoot = i < roots;
            String reason = i == occCount - 1 ? "\"conflict\"" : "null";
            occ.append("{\"i\":").append(i)
               .append(",\"p\":").append(isRoot ? -1 : 0)
               .append(",\"ga\":").append(i % 2)
               .append(",\"v\":0,\"e\":").append(isRoot ? 1 : 0)
               .append(",\"m\":0,\"t\":\"jar\",\"o\":").append(reason).append('}');
        }
        String json = "{\"project\":{},\"gas\":[{\"g\":\"a\",\"a\":\"x\",\"v\":[\"1\"]},"
                + "{\"g\":\"b\",\"a\":\"y\",\"v\":[\"1\"]}],\"occ\":[" + occ + "],"
                + "\"moduleRoots\":[],\"modules\":[{\"artifactId\":\"x\"}]}";
        File f = tmp.newFile("g.json");
        java.nio.file.Files.write(f.toPath(), json.getBytes("UTF-8"));
        return f;
    }

    @Test
    public void statsReadFromJsonWithoutTouchingTheProject() throws Exception {
        File f = writeGraph(10, 2);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream old = System.out;
        System.setOut(new PrintStream(buf, true, "UTF-8"));
        try {
            assertEquals(0, GraphCommand.statsOfJson(f));
        } finally {
            System.setOut(old);
        }
        String out = buf.toString("UTF-8");
        assertTrue("occ 数", out.contains("occ        10"));
        assertTrue("roots 数", out.contains("roots      2"));
        assertTrue("ga 数", out.contains("ga         2"));
        assertTrue("effective 含根", out.contains("effective  2 (non-root 0)"));
        assertTrue("conflict 数", out.contains("conflict   1"));
    }

    @Test
    public void brokenJsonFailsWithoutThrowing() throws Exception {
        File f = tmp.newFile("bad.json");
        java.nio.file.Files.write(f.toPath(), "{not json".getBytes("UTF-8"));
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream old = System.err;
        System.setErr(new PrintStream(err, true, "UTF-8"));
        try {
            assertEquals("坏 JSON 要返回非 0，不能抛栈", 2, GraphCommand.statsOfJson(f));
        } finally {
            System.setErr(old);
        }
    }
}
