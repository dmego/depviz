package cn.dmego.dev.depviz.env;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class EnvProbeTest {

    @Test
    public void xmxTiers() {
        long gb = 1024L * 1024 * 1024;
        assertEquals("物理内存不到 1G 时不注入堆上限", "", EnvProbe.xmxFor(gb / 2));
        assertEquals("1.2G", "384m", EnvProbe.xmxFor(gb + gb / 5));
        assertEquals("1.8G", "512m", EnvProbe.xmxFor(gb * 18 / 10));
        assertEquals("3G", "768m", EnvProbe.xmxFor(gb * 3));
        assertEquals("8G", "1024m", EnvProbe.xmxFor(gb * 8));
    }

    @Test
    public void detectsMavenAndJava() {
        String mvn = EnvProbe.mavenVersion("mvn");
        assertTrue("应能探测到 Maven 版本，实得 [" + mvn + "]", mvn.matches("\\d+\\.\\d+.*"));
        String java = EnvProbe.javaVersion(System.getProperty("java.home"));
        assertTrue("应能探测到 Java 版本，实得 [" + java + "]", !java.isEmpty());
    }
}
