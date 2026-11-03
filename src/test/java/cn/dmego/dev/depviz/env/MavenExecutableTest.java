package cn.dmego.dev.depviz.env;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link MavenExecutable}：把配置里的 mvn 名字变成 Windows 上真能执行的文件。
 *
 * <p>触发这个类的问题（用户实测）：
 * <pre>
 * 扫描失败: 启动 Maven 失败: Cannot run program "D:\apache-maven-3.3.9\bin\mvn"
 *   (in directory "D:\code\Demo"): CreateProcess error=193, %1 不是有效的 Win32 应用程序。
 * </pre>
 * {@code bin\mvn} 是给 sh 用的脚本，不是 PE 文件；Windows 上能跑的是 {@code mvn.cmd}。
 *
 * <p>测试<b>不依赖宿主操作系统</b>：{@link MavenExecutable#resolve(String, boolean, String)}
 * 把「是不是 Windows」和「PATH 长什么样」都做成入参，所以 macOS/Linux CI 上也能量到
 * Windows 分支的行为。
 */
public class MavenExecutableTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    /** 非 Windows 上原样返回：那里 mvn 就是可执行脚本，补扩展名反而会坏。 */
    @Test
    public void nonWindowsKeepsTheNameAsIs() {
        assertEquals("mvn", MavenExecutable.resolve("", false, null));
        assertEquals("/opt/maven/bin/mvn", MavenExecutable.resolve("/opt/maven/bin/mvn", false, null));
    }

    /** 空的配置回落 "mvn"，不是空串（空串会让 ProcessBuilder 直接抛异常）。 */
    @Test
    public void blankFallsBackToTheDefaultName() {
        assertEquals("mvn", MavenExecutable.resolve(null, false, null));
        assertEquals("mvn", MavenExecutable.resolve("   ", false, null));
    }

    /**
     * 裸名字在 Windows 上补成 mvn.cmd。
     *
     * <p>不能指望 CreateProcess 自己找：它只按 PATHEXT 补 .exe，永远补不到 .cmd。
     */
    @Test
    public void bareNameGetsCmdOnWindowsWhenPathHasNoMaven() {
        assertEquals("mvn.cmd", MavenExecutable.resolve("mvn", true, null));
        assertEquals("mvn.cmd", MavenExecutable.resolve("", true, "/nonexistent"));
    }

    /** PATH 里有 mvn.cmd 时直接用绝对路径，不靠当前目录的偶然状态。 */
    @Test
    public void bareNameIsResolvedAgainstPath() throws Exception {
        File bin = tmp.newFolder("maven/bin");
        File cmd = new File(bin, "mvn.cmd");
        Files.write(cmd.toPath(), "@echo off".getBytes(StandardCharsets.UTF_8));

        String out = MavenExecutable.resolve("mvn", true, bin.getAbsolutePath());
        assertEquals(cmd.getAbsolutePath(), out);
    }

    /** PATH 里只有 mvn.bat（Maven 2 时代）也要认。 */
    @Test
    public void batOnPathIsAccepted() throws Exception {
        File bin = tmp.newFolder("maven-old/bin");
        File bat = new File(bin, "mvn.bat");
        Files.write(bat.toPath(), "@echo off".getBytes(StandardCharsets.UTF_8));

        assertEquals(bat.getAbsolutePath(),
                MavenExecutable.resolve("mvn", true, bin.getAbsolutePath()));
    }

    /** 用户填了带目录的 bin\mvn —— 正是报错里那条路径，要换成同目录的 mvn.cmd。 */
    @Test
    public void explicitScriptPathGetsTheCmdSibling() throws Exception {
        File bin = tmp.newFolder("apache-maven-3.3.9/bin");
        File script = new File(bin, "mvn");
        File cmd = new File(bin, "mvn.cmd");
        Files.write(script.toPath(), "#!/bin/sh".getBytes(StandardCharsets.UTF_8));
        Files.write(cmd.toPath(), "@echo off".getBytes(StandardCharsets.UTF_8));

        assertEquals(cmd.getAbsolutePath(),
                MavenExecutable.resolve(script.getAbsolutePath(), true, null));
    }

    /**
     * 同目录没有 .cmd/.bat/.exe 时原样返回。
     *
     * <p>报错里带着用户自己写的那条路径，比换成猜的名字好排查 —— 猜错了会变成
     * 「文件不存在」，而他明明看得见那个文件。
     */
    @Test
    public void explicitPathWithoutAnyExecutableSiblingIsKept() throws Exception {
        File bin = tmp.newFolder("maven-weird/bin");
        File script = new File(bin, "mvn");
        Files.write(script.toPath(), "#!/bin/sh".getBytes(StandardCharsets.UTF_8));

        assertEquals(script.getAbsolutePath(),
                MavenExecutable.resolve(script.getAbsolutePath(), true, null));
    }

    /** 已经写了 .cmd 就别再动 —— 用户的配置要原样生效。 */
    @Test
    public void alreadyQualifiedNamesAreUntouched() {
        assertEquals("D:\\maven\\bin\\mvn.cmd",
                MavenExecutable.resolve("D:\\maven\\bin\\mvn.cmd", true, null));
        assertEquals("D:\\maven\\bin\\mvn.bat",
                MavenExecutable.resolve("D:\\maven\\bin\\mvn.bat", true, null));
        assertEquals("D:\\maven\\bin\\mvn.exe",
                MavenExecutable.resolve("D:\\maven\\bin\\mvn.exe", true, null));
    }

    /** 扩展名大小写不敏感（Windows 配置里常见 .CMD）。 */
    @Test
    public void suffixMatchIsCaseInsensitive() {
        assertEquals("D:\\maven\\bin\\MVN.CMD",
                MavenExecutable.resolve("D:\\maven\\bin\\MVN.CMD", true, null));
    }

    /**
     * 用户把安装目录整条粘进来（{@code D:\apache-maven-3.3.9}）时要往下找一层。
     *
     * <p>设置里那一栏叫「Maven 路径」，从官网解压完复制到的是安装目录，
     * 不是 {@code bin/mvn}。指给 ProcessBuilder 一个目录必定失败。
     */
    @Test
    public void installDirIsResolvedToItsBinScript() throws Exception {
        File home = tmp.newFolder("apache-maven-3.3.9");
        File bin = new File(home, "bin");
        assertTrue(bin.mkdirs());
        File cmd = new File(bin, "mvn.cmd");
        Files.write(cmd.toPath(), "@echo off".getBytes(StandardCharsets.UTF_8));

        assertEquals(cmd.getAbsolutePath(),
                MavenExecutable.resolve(home.getAbsolutePath(), true, null));
    }

    /** 已经填到 {@code bin} 那一层也要认。 */
    @Test
    public void binDirIsResolvedToItsSiblingScript() throws Exception {
        File home = tmp.newFolder("maven-3.9");
        File bin = new File(home, "bin");
        assertTrue(bin.mkdirs());
        File cmd = new File(bin, "mvn.cmd");
        Files.write(cmd.toPath(), "@echo off".getBytes(StandardCharsets.UTF_8));

        assertEquals(cmd.getAbsolutePath(),
                MavenExecutable.resolve(bin.getAbsolutePath(), true, null));
    }

    /** 非 Windows 上安装目录里的 {@code bin/mvn} 一样要找到。 */
    @Test
    public void installDirWorksOnUnixToo() throws Exception {
        File home = tmp.newFolder("maven-unix");
        File bin = new File(home, "bin");
        assertTrue(bin.mkdirs());
        File mvn = new File(bin, "mvn");
        Files.write(mvn.toPath(), "#!/bin/sh".getBytes(StandardCharsets.UTF_8));

        assertEquals(mvn.getAbsolutePath(),
                MavenExecutable.resolve(home.getAbsolutePath(), false, null));
    }

    /** 空目录不是 Maven 安装目录，别自作主张 —— 原样返回，报错里能看到用户写的是什么。 */
    @Test
    public void plainDirectoryWithoutMavenIsKept() throws Exception {
        File dir = tmp.newFolder("not-a-maven");
        assertEquals(dir.getAbsolutePath(),
                MavenExecutable.resolve(dir.getAbsolutePath(), true, null));
    }

    /** 默认名字与 {@code ScanRequest.Builder} 的默认值必须一致，否则两处会飘。 */
    @Test
    public void defaultNameMatchesScanRequest() {
        assertTrue(MavenExecutable.DEFAULT_NAME.equals("mvn"));
    }
}
