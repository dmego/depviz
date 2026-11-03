package cn.dmego.dev.depviz.scan;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class MvnRunnerTest {

    @org.junit.Rule
    public final org.junit.rules.TemporaryFolder tmp =
            new org.junit.rules.TemporaryFolder();

    /** 仓库内的 fixture 项目：换机器也能真跑 Maven，不依赖作者主目录。 */
    private static final File DEMO = new File("src/test/resources/projects/demo");

    @Test
    public void commandUsesFullyQualifiedPluginCoordinate() {
        ScanRequest r = new ScanRequest.Builder()
                .projectDir(new File("/tmp"))
                .outputFile(new File("/tmp/x/tree.txt"))
                .build();
        List<String> cmd = MvnCommand.build(r);
        assertTrue(cmd.contains("org.apache.maven.plugins:maven-dependency-plugin:3.6.0:tree"));
        assertTrue("-Dverbose 必须带上", cmd.contains("-Dverbose"));
        assertTrue("-DappendOutput=true 必须带上", cmd.contains("-DappendOutput=true"));
        assertTrue("默认在线，不该有 -o", !cmd.contains("-o"));
    }

    @Test
    public void offlineCanBeEnabled() {
        ScanRequest r = new ScanRequest.Builder()
                .projectDir(new File("/tmp")).outputFile(new File("/tmp/t.txt"))
                .offline(true).build();
        assertTrue(MvnCommand.build(r).contains("-o"));
    }

    @Test
    public void offlineCanBeDisabled() {
        ScanRequest r = new ScanRequest.Builder()
                .projectDir(new File("/tmp")).outputFile(new File("/tmp/t.txt"))
                .offline(false).build();
        assertTrue(!MvnCommand.build(r).contains("-o"));
    }

    /** 没配并行时一个 `-T` 都不能有：老用户升级后命令必须逐字不变。 */
    @Test
    public void noThreadFlagByDefault() {
        ScanRequest r = new ScanRequest.Builder()
                .projectDir(new File("/tmp")).outputFile(new File("/tmp/t.txt"))
                .build();
        assertFalse(MvnCommand.build(r).contains("-T"));
    }

    @Test
    public void threadFlagGoesRightAfterBatchMode() {
        ScanRequest r = new ScanRequest.Builder()
                .projectDir(new File("/tmp")).outputFile(new File("/tmp/t.txt"))
                .threads("4").build();
        List<String> cmd = MvnCommand.build(r);
        int t = cmd.indexOf("-T");
        assertTrue("配了并行就要带 -T", t > 0);
        assertEquals("线程数紧跟 -T", "4", cmd.get(t + 1));
        // Maven 的 -T 是构建参数，必须排在 goal 之前，否则被当成目标名
        assertTrue("-T 要在 goal 之前",
                t < cmd.indexOf("org.apache.maven.plugins:maven-dependency-plugin:3.6.0:tree"));
        assertEquals("-B 之后就是 -T", 2, t);
    }

    /** `4C` 是 Maven 自己认的写法（按核数换算），不能当成非法值丢掉。 */
    @Test
    public void coreSuffixIsPassedThrough() {
        ScanRequest r = new ScanRequest.Builder()
                .projectDir(new File("/tmp")).outputFile(new File("/tmp/t.txt"))
                .threads("2C").build();
        List<String> cmd = MvnCommand.build(r);
        assertEquals("2C", cmd.get(cmd.indexOf("-T") + 1));
    }

    /**
     * 写 1 或 off 的意图都是「别并行」。
     *
     * <p>{@code -T 1} 不是等价写法：它仍然换成并行构建器，模块树顺序照样会漂，
     * 只是恰好只有一个线程 —— 用户写 1 显然不是想要这个。
     */
    @Test
    public void oneOrOffMeansSerial() {
        for (String v : new String[] {"1", "off", "none", "-", "", "  "}) {
            ScanRequest r = new ScanRequest.Builder()
                    .projectDir(new File("/tmp")).outputFile(new File("/tmp/t.txt"))
                    .threads(v).build();
            assertFalse("threads=" + v + " 不该带 -T", MvnCommand.build(r).contains("-T"));
        }
    }

    @Test
    public void extraOptionsArePassedThrough() {
        ScanRequest r = new ScanRequest.Builder()
                .projectDir(new File("/tmp")).outputFile(new File("/tmp/t.txt"))
                .settingsXml("/s.xml").localRepo("/r").profile("dev").profile("cloud")
                .build();
        List<String> cmd = MvnCommand.build(r);
        assertTrue(cmd.contains("-s"));
        assertTrue(cmd.contains("/s.xml"));
        assertEquals("两个 profile", 2, countOf(cmd, "-P"));
        assertTrue(cmd.contains("-Dmaven.repo.local=/r"));
    }

    @Test
    public void runsOnDemoAndProducesKnownJdkVariant() throws Exception {
        assertTrue("仓库内 demo POM 必须存在", new File(DEMO, "pom.xml").isFile());
        File dir = new File(System.getProperty("java.io.tmpdir"), "depviz-mvn-" + System.nanoTime());
        assertTrue(dir.mkdirs());
        ScanRequest req = new ScanRequest.Builder()
                .projectDir(DEMO)
                .outputFile(new File(dir, "tree.txt"))
                // 显式离线：这条测的是「Maven 能被拉起并产出可解析的树」，
                // 不是「网络通不通」。默认在线会让单测在无网环境下挂掉，
                // 也会为了一个 demo 项目多等几十秒。
                .offline(true)
                .timeoutSeconds(300)
                .build();
        final List<String> log = new ArrayList<String>();
        ScanOutcome out = MvnRunner.run(req, log::add, null);
        assertTrue("扫描失败: " + out.message, out.isOk());
        int lines = countLines(out.treeFile);
        assertTrue("demo 依赖树行数应为 JDK 对应的 450 或 451，实得 " + lines,
                lines == 450 || lines == 451);
        assertTrue("日志必须被抽到", log.size() > 3);
    }

    private static int countLines(File f) throws Exception {
        int n = 0;
        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                new java.io.FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8));
        try {
            while (r.readLine() != null) {
                n++;
            }
        } finally {
            r.close();
        }
        return n;
    }

    private static int countOf(List<String> list, String v) {
        int n = 0;
        for (String s : list) {
            if (v.equals(s)) {
                n++;
            }
        }
        return n;
    }

    /**
     * 超时必须杀掉整棵进程树。
     *
     * <p>{@code mvn} 是 shell 脚本，只 destroy 脚本会留下孤儿 JVM 继续吃
     * {@code -Xmx1024m}。这里用一个会 fork 子进程并长睡的假 maven 验证。
     */
    @Test
    public void timeoutKillsChildProcesses() throws Exception {
        File dir = tmp.newFolder("slow");
        File script = new File(dir, "mvn");
        // 起一个长睡子进程，然后自己也睡；只杀父进程的话子进程会活着
        java.nio.file.Files.write(script.toPath(), (
                "#!/bin/sh\n"
                + "sleep 60 &\n"
                + "CHILD=$!\n"
                + "echo $CHILD > " + new File(dir, "child.pid").getAbsolutePath() + "\n"
                + "wait\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(script.setExecutable(true));

        ScanRequest req = new ScanRequest.Builder()
                .projectDir(dir)
                .outputFile(new File(dir, "tree.txt"))
                .mavenExecutable(script.getAbsolutePath())
                .timeoutSeconds(1)
                .build();
        long t0 = System.currentTimeMillis();
        ScanOutcome out = MvnRunner.run(req, line -> { }, new Canceller());
        long elapsed = System.currentTimeMillis() - t0;

        assertTrue("必须按超时失败", !out.isOk());
        assertTrue("超时信息要写清楚", out.message.contains("超过"));
        assertTrue("1 秒超时不该跑这么久: " + elapsed, elapsed < 15000);

        File pidFile = new File(dir, "child.pid");
        if (pidFile.isFile()) {
            String pid = new String(java.nio.file.Files.readAllBytes(pidFile.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            Thread.sleep(500);
            // 只能用 kill -0 探活：macOS 没有 /proc，写成 File("/proc/"+pid).exists()
            // 会让这条断言永远为假、等于什么都没测（变异测试验证过）。
            assertFalse("子进程 " + pid + " 必须被杀掉，不能留孤儿", isAlive(pid));
        }
    }

    private static boolean isAlive(String pid) {
        try {
            Process p = new ProcessBuilder("kill", "-0", pid).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 「缺依赖」不能靠 {@code contains("is missing")} 判断。
     *
     * <p>dynamic-tp 实测：Maven 对**插件版本没写**这类模型警告同样说 is missing，
     * 一个项目就有 83 行，会被算成「83 个依赖未能解析」，元数据条无故变黄、
     * 还冒出点了也不会好的「联网重试」按钮（真机复现过）。
     */
    @Test
    public void modelWarningsAreNotMissingDependencies() {
        assertFalse(MvnRunner.Drainer.isMissingDependency(
                "[WARNING] 'build.plugins.plugin.version' for "
                        + "org.apache.maven.plugins:maven-jar-plugin is missing. @ "
                        + "org.dromara.dynamictp:dynamic-tp-all:${revision}, "
                        + "/p/pom.xml, line 226, column 21"));
        assertFalse(MvnRunner.Drainer.isMissingDependency(
                "[WARNING] Some problems were encountered while building the effective model"));
    }

    /** 真缺 POM 的那一行（本地实测 `mvn -o dependency:tree` 的原样输出）才算数。 */
    @Test
    public void realMissingPomsAreCounted() {
        assertTrue(MvnRunner.Drainer.isMissingDependency(
                "[WARNING] The POM for com.nonexistent.ns:does-not-exist-xyz:jar:9.9.9 "
                        + "is missing, no dependency information available"));
    }
}
