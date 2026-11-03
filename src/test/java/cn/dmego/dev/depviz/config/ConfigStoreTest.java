package cn.dmego.dev.depviz.config;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class ConfigStoreTest {

    private static File tempDir() throws Exception {
        File d = Files.createTempDirectory("depviz-cfg").toFile();
        d.deleteOnExit();
        return d;
    }

    @Test
    public void missingFileYieldsDefaults() throws Exception {
        ConfigStore c = new ConfigStore(new File(tempDir(), "nope.json")).load();
        assertEquals("", c.maven());
        assertTrue("默认在线：离线会让 BOM 类项目静默失败", !c.offline());
        assertEquals("", c.profiles());
        String configuredHome = System.getenv(ConfigStore.HOME_ENV);
        File expectedDataDir = configuredHome == null || configuredHome.trim().isEmpty()
                ? new File(System.getProperty("user.home"), ".depviz")
                : new File(configuredHome.trim(), "data");
        assertEquals("默认数据目录应遵循 DEPVIZ_HOME，未设置时使用 ~/.depviz",
                expectedDataDir.getAbsolutePath(), c.dataDir());
        assertEquals("info", c.logLevel());
        assertEquals("info", c.fileLogLevel());
        assertEquals("auto", c.xmx());
        assertEquals("auto 跟随物理内存分档", cn.dmego.dev.depviz.env.EnvProbe.xmxForThisMachine(),
                c.resolvedXmx());
        assertEquals("默认不并行：-T 会让模块树的顺序与注解位置漂移", "", c.threads());
    }

    @Test
    public void roundTripsAllElevenKeys() throws Exception {
        File f = new File(tempDir(), "config.json");
        ConfigStore c = new ConfigStore(f);
        c.maven("/opt/mvn");
        c.javaHome("/opt/jdk8");
        c.settingsXml("/s.xml");
        c.localRepo("/repo");
        c.offline(true);
        c.profiles("dev, cloud");
        c.dataDir("/data");
        c.logLevel("DEBUG");
        c.fileLogLevel("warn");
        c.xmx("512m");
        c.threads("4C");
        c.save();

        ConfigStore back = new ConfigStore(f).load();
        assertEquals("/opt/mvn", back.maven());
        assertEquals("/opt/jdk8", back.javaHome());
        assertEquals("/s.xml", back.settingsXml());
        assertEquals("/repo", back.localRepo());
        assertTrue(back.offline());
        assertEquals("dev, cloud", back.profiles());
        assertEquals("/data", back.dataDir());
        assertEquals("级别解析后统一小写", "debug", back.logLevel());
        assertEquals("warn", back.fileLogLevel());
        assertEquals("用户配了就用用户的，不再分档", "512m", back.resolvedXmx());
        assertEquals("4C 原样透传：Maven 自己按核数换算", "4C", back.threads());
        String[] ps = back.profileList();
        assertEquals("两个 profile", 2, ps.length);
        assertEquals("dev", ps[0]);
        assertEquals("cloud", ps[1]);
    }

    @Test
    public void savedFileIsIndentedForHumans() throws Exception {
        File f = new File(tempDir(), "config.json");
        ConfigStore c = new ConfigStore(f);
        c.dataDir("/data");
        c.save();
        String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        assertTrue("配置是给人改的，必须是多行缩进:\n" + text, text.indexOf('\n') > 0);
        assertTrue(text.contains("\n  \"maven\": \"\""));
        // 缩进不能破坏可解析性
        Map<String, Object> m = JsonIn.parseObject(text);
        assertEquals("/data", m.get("dataDir"));
    }

    @Test
    public void unknownFieldsArePreserved() throws Exception {
        File f = new File(tempDir(), "config.json");
        Files.write(f.toPath(), ("{\"maven\":\"/m\",\"futureFlag\":\"keep-me\","
                + "\"nested\":{\"a\":1,\"b\":[1,2]}}").getBytes(StandardCharsets.UTF_8));

        ConfigStore c = new ConfigStore(f).load();
        assertEquals("/m", c.maven());
        c.offline(true);
        c.save();

        Map<String, Object> m = JsonIn.parseObject(
                new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        assertEquals("未知字段不能丢", "keep-me", m.get("futureFlag"));
        assertTrue("嵌套未知字段也要保留", m.get("nested") instanceof Map);
        assertEquals("已知字段被更新", Boolean.TRUE, m.get("offline"));
    }

    @Test
    public void brokenFileFallsBackToDefaults() throws Exception {
        File f = new File(tempDir(), "config.json");
        Files.write(f.toPath(), "{not json".getBytes(StandardCharsets.UTF_8));
        ConfigStore c = new ConfigStore(f).load();
        assertTrue("坏配置不能抛异常", !c.offline());
        assertEquals("", c.maven());
    }

    @Test
    public void jsonInParsesEscapesAndNumbers() {
        Map<String, Object> m = JsonIn.parseObject("{\"s\":\"a\\\"b\\nc\",\"n\":-1.5,\"b\":true}");
        assertEquals("a\"b\nc", m.get("s"));
        assertEquals(Double.valueOf(-1.5), m.get("n"));
        assertEquals(Boolean.TRUE, m.get("b"));
    }
}
