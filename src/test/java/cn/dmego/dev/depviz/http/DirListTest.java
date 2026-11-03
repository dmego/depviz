package cn.dmego.dev.depviz.http;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@code /api/list-dir}：页内目录浏览器的数据源。
 *
 * <p>这条路由取代了原先的 {@code /api/pick-dir}（服务端 AWT 弹框）。换掉的理由是
 * macOS 上会蹦出一个属于 java 程序的窗口 —— 用户分不清它和终端里的服务是不是一回事，
 * 而且 headless / SSH 下根本弹不出来。现在前端自己渲染目录树，后端只负责列目录。
 */
public class DirListTest extends HttpTestBase {

    /** 只列子目录，不列文件；子目录里有没有 pom.xml 要标出来。 */
    @Test
    public void listsSubdirectoriesWithPomFlag() throws Exception {
        File root = tmp.newFolder("browse");
        File withPom = new File(root, "mod-a");
        File withoutPom = new File(root, "mod-b");
        assertTrue(withPom.mkdirs());
        assertTrue(withoutPom.mkdirs());
        Files.write(new File(withPom, "pom.xml").toPath(),
                "<project/>".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(root, "pom.xml").toPath(),
                "<project/>".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(root, "notes.txt").toPath(), "x".getBytes(StandardCharsets.UTF_8));

        Resp r = send("GET", "/api/list-dir?path=" + enc(root.getAbsolutePath()), null);
        assertEquals(200, r.status);
        // 用规范路径比较：macOS 上 /var/folders/... 与 /private/var/folders/... 是同一个目录，
        // 而 TemporaryFolder 给的是前者。字符串直接比会假失败。
        assertTrue("要带上当前路径", r.body.contains("\"path\":\"" + canon(root) + "\""));
        assertTrue("根目录自己有 pom 要标", r.body.contains("\"hasPom\":true"));
        assertTrue("子目录 mod-a 要出现", r.body.contains("\"mod-a\""));
        assertTrue("子目录 mod-b 要出现", r.body.contains("\"mod-b\""));
        assertFalse("文件不列", r.body.contains("notes.txt"));
    }

    /** 路径写错一个字符时落到父目录，别让用户对着 500 猜。 */
    @Test
    public void missingPathFallsBackToParent() throws Exception {
        File root = tmp.newFolder("browse2");
        assertTrue(new File(root, "child").mkdirs());

        Resp r = send("GET", "/api/list-dir?path=" + enc(root.getAbsolutePath() + "/nope"), null);
        assertEquals(200, r.status);
        assertTrue("应该退到存在的父目录", r.body.contains("\"path\":\"" + canon(root) + "\""));
        assertTrue(r.body.contains("\"child\""));
    }

    /** 相对路径必须 400 —— 与服务进程的 cwd 不可预期是同一个理由。 */
    @Test
    public void relativePathIs400() throws Exception {
        Resp r = send("GET", "/api/list-dir?path=relative", null);
        assertEquals(400, r.status);
        assertTrue(r.body.contains("必须是绝对路径"));
    }

    /** 不带 path 时默认列用户主目录，前端第一次打开弹层就能用。 */
    @Test
    public void emptyPathDefaultsToHome() throws Exception {
        Resp r = send("GET", "/api/list-dir", null);
        assertEquals(200, r.status);
        assertTrue(r.body.contains("\"home\":"));
    }

    /** 隐藏目录不进列表：.git / .idea 混在里面只会碍事。 */
    @Test
    public void hiddenDirectoriesAreSkipped() throws Exception {
        File root = tmp.newFolder("browse3");
        assertTrue(new File(root, ".hidden").mkdirs());
        assertTrue(new File(root, "visible").mkdirs());

        Resp r = send("GET", "/api/list-dir?path=" + enc(root.getAbsolutePath()), null);
        assertEquals(200, r.status);
        assertFalse("隐藏目录不该出现", r.body.contains("\".hidden\""));
        assertTrue(r.body.contains("\"visible\""));
    }

    /** 面包屑由服务端算好：Windows 上前端按 '/' 切会把整串当成一段，点哪都回不去。 */
    @Test
    public void crumbsWalkFromTheRootDownToTheCurrentDir() throws Exception {
        File root = tmp.newFolder("browse4");
        File deep = new File(root, "a/b");
        assertTrue(deep.mkdirs());

        Resp r = send("GET", "/api/list-dir?path=" + enc(deep.getAbsolutePath()), null);
        assertEquals(200, r.status);
        String crumbs = between(r.body, "\"crumbs\":[", "]");
        assertTrue("面包屑要包含当前目录自己", crumbs.contains("\"path\":\"" + canon(deep) + "\""));
        assertTrue("面包屑要包含中间那一层", crumbs.contains("\"name\":\"a\""));
        assertTrue("面包屑要从根开始", crumbs.startsWith("{\"name\":\"/\",\"path\":\"/\"}"));
        assertTrue("最后一段必须是当前目录", crumbs.endsWith("\"path\":\"" + canon(deep) + "\"}"));
    }

    /** 根列表必须有 —— Windows 上「上一级」到 C:\\ 就到头了，没有它切不到 D 盘。 */
    @Test
    public void rootsAreAlwaysGiven() throws Exception {
        File root = tmp.newFolder("browse5");
        Resp r = send("GET", "/api/list-dir?path=" + enc(root.getAbsolutePath()), null);
        assertEquals(200, r.status);
        assertTrue("要有 roots 字段", r.body.contains("\"roots\":["));
        assertTrue("至少一项", r.body.contains("\"name\":\"/\""));
    }

    /** D: 这种写法在 Windows 上要补成 D:\ —— 用户觉得自己输的就是盘符。 */
    @Test
    public void bareDriveLetterKeepsItsSlash() {
        String in = "D:";
        String out = DirList.normalizeRoot(in);
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            assertEquals("D:\\", out);
        } else {
            assertEquals("非 Windows 上原样返回", in, out);
        }
    }

    /** D:/code 里的正斜杠在 Windows 上要转成反斜杠，否则 isAbsolute() 认不出。 */
    @Test
    public void forwardSlashesAreNormalizedOnWindows() {
        String out = DirList.normalizeRoot("D:/code/demo");
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            assertEquals("D:\\code\\demo", out);
        } else {
            assertEquals("D:/code/demo", out);
        }
    }

    /** 取 {@code [start, end)} 之间的子串，找不到返回空串。 */
    private static String between(String s, String start, String end) {
        int a = s.indexOf(start);
        if (a < 0) {
            return "";
        }
        int b = s.indexOf(end, a + start.length());
        return b < 0 ? "" : s.substring(a + start.length(), b);
    }

    private static String canon(File f) throws Exception {
        return f.getCanonicalPath();
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
