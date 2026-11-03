package cn.dmego.dev.depviz.config;

import cn.dmego.dev.depviz.json.JsonOut;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/** 读取并写入配置，同时保留未知字段以兼容新版本。 */
public final class ConfigStore {

    private final File file;
    private final Map<String, Object> extra = new LinkedHashMap<String, Object>();

    private String maven = "";
    private String javaHome = "";
    private String settingsXml = "";
    private String localRepo = "";
    /** 默认在线模式；离线模式需显式启用。 */
    private boolean offline;
    private String profiles = "";
    private String dataDir;
    /** 控制台日志级别，默认 {@code info}；命令行参数优先。 */
    private String logLevel = "info";
    /** 文件日志级别，默认 {@code info}。 */
    private String fileLogLevel = "info";
    /** Maven 堆上限：{@code auto} 自动设置，{@code off} 禁用，其余值作为 {@code -Xmx}。 */
    private String xmx = "auto";
    /** Maven {@code -T} 参数；空值、{@code 1} 或 {@code off} 表示串行执行。 */
    private String threads = "";

    /** 可选发行版根目录，配置、数据和日志均保存于此。 */
    public static final String HOME_ENV = "DEPVIZ_HOME";

    public ConfigStore(File file) {
        this.file = file;
        this.dataDir = defaultDataDir().getAbsolutePath();
    }

    /** 发行版根目录；未设置 {@code DEPVIZ_HOME} 时返回 {@code null}。 */
    public static File home() {
        String v = System.getenv(HOME_ENV);
        if (v == null || v.trim().isEmpty()) {
            return null;
        }
        return new File(v.trim());
    }

    /** 默认配置文件：{@code $DEPVIZ_HOME/config.json}，否则 {@code ~/.depviz/config.json}。 */
    public static ConfigStore defaultLocation() {
        File home = home();
        if (home != null) {
            return new ConfigStore(new File(home, "config.json"));
        }
        return new ConfigStore(new File(userDepviz(), "config.json"));
    }

    /** 数据目录：{@code $DEPVIZ_HOME/data}，否则 {@code ~/.depviz}。 */
    public static File defaultDataDir() {
        File home = home();
        return home != null ? new File(home, "data") : userDepviz();
    }

    /** 返回 {@code $DEPVIZ_HOME/logs}；环境变量未设置时返回 {@code null}。 */
    public static File defaultLogDir() {
        File home = home();
        return home == null ? null : new File(home, "logs");
    }

    private static File userDepviz() {
        return new File(System.getProperty("user.home"), ".depviz");
    }

    public File file() {
        return file;
    }

    /** {@link #file()} 的别名，供诊断信息使用。 */
    public File location() {
        return file;
    }

    /** 加载配置；文件缺失或无效时保留默认值。 */
    public ConfigStore load() {
        extra.clear();
        if (!file.isFile()) {
            return this;
        }
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            Map<String, Object> m = JsonIn.parseObject(new String(bytes, StandardCharsets.UTF_8));
            maven = str(m, "maven", maven);
            javaHome = str(m, "javaHome", javaHome);
            settingsXml = str(m, "settingsXml", settingsXml);
            localRepo = str(m, "localRepo", localRepo);
            offline = bool(m, "offline", offline);
            profiles = str(m, "profiles", profiles);
            dataDir = str(m, "dataDir", dataDir);
            logLevel = str(m, "logLevel", logLevel);
            fileLogLevel = str(m, "fileLogLevel", fileLogLevel);
            xmx = str(m, "xmx", xmx);
            threads = str(m, "threads", threads);
            ConfigJson.retainUnknown(m, extra);
            return this;
        } catch (IOException e) {
            return this;
        } catch (RuntimeException e) {
            // 配置无效时保留默认值。
            return this;
        }
    }

    /** 以缩进 JSON 原子写入配置，必要时创建父目录。 */
    public void save() throws IOException {
        synchronized (SAVE_LOCK) {
            doSave();
        }
    }

    /** 串行处理并发配置写入。 */
    private static final Object SAVE_LOCK = new Object();

    private void doSave() throws IOException {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建配置目录: " + dir);
        }
        byte[] bytes = toJson().getBytes(StandardCharsets.UTF_8);
        java.nio.file.Path target = file.toPath();
        java.nio.file.Path tmp = null;
        try {
            // 临时文件使用唯一名称，避免并发写入互相覆盖。
            tmp = Files.createTempFile(dir == null ? target.getParent() : dir.toPath(),
                    file.getName() + "-", ".tmp");
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            tmp = null;
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // 遗留临时文件不影响已保存的配置。
                }
            }
        }
    }

    /** 将已知配置和保留字段序列化为缩进 JSON。 */
    public String toJson() {
        JsonOut o = JsonOut.pretty();
        o.beginObject();
        o.field("maven", maven);
        o.field("javaHome", javaHome);
        o.field("settingsXml", settingsXml);
        o.field("localRepo", localRepo);
        o.field("offline", offline);
        o.field("profiles", profiles);
        o.field("dataDir", dataDir);
        o.field("logLevel", logLevel);
        o.field("fileLogLevel", fileLogLevel);
        o.field("xmx", xmx);
        o.field("threads", threads);
        for (Map.Entry<String, Object> e : extra.entrySet()) {
            o.name(e.getKey());
            ConfigJson.writeAny(o, e.getValue());
        }
        o.endObject();
        return o.toString();
    }

    // 读写方法

    public String maven() {
        return maven;
    }

    public void maven(String v) {
        maven = v == null ? "" : v;
    }

    public String javaHome() {
        return javaHome;
    }

    public void javaHome(String v) {
        javaHome = v == null ? "" : v;
    }

    public String settingsXml() {
        return settingsXml;
    }

    public void settingsXml(String v) {
        settingsXml = v == null ? "" : v;
    }

    public String localRepo() {
        return localRepo;
    }

    public void localRepo(String v) {
        localRepo = v == null ? "" : v;
    }

    public boolean offline() {
        return offline;
    }

    public void offline(boolean v) {
        offline = v;
    }

    public String profiles() {
        return profiles;
    }

    /** 逗号分隔的 profile 列表，去掉空白项。 */
    public String[] profileList() {
        if (profiles == null || profiles.trim().isEmpty()) {
            return new String[0];
        }
        String[] raw = profiles.split(",");
        int n = 0;
        String[] tmp = new String[raw.length];
        for (String s : raw) {
            if (!s.trim().isEmpty()) {
                tmp[n++] = s.trim();
            }
        }
        String[] out = new String[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }

    public void profiles(String v) {
        profiles = v == null ? "" : v;
    }

    public String logLevel() {
        return logLevel;
    }

    public void logLevel(String v) {
        logLevel = v == null || v.trim().isEmpty() ? "info" : v.trim().toLowerCase();
    }

    public String fileLogLevel() {
        return fileLogLevel;
    }

    public void fileLogLevel(String v) {
        fileLogLevel = v == null || v.trim().isEmpty() ? "info" : v.trim().toLowerCase();
    }

    public String xmx() {
        return xmx;
    }

    public void xmx(String v) {
        xmx = v == null || v.trim().isEmpty() ? "auto" : v.trim();
    }

    /** 解析堆上限配置；返回空串表示不注入 {@code -Xmx}。 */
    public String resolvedXmx() {
        String v = xmx == null ? "" : xmx.trim();
        if (v.isEmpty() || "auto".equalsIgnoreCase(v)) {
            return cn.dmego.dev.depviz.env.EnvProbe.xmxForThisMachine();
        }
        if ("off".equalsIgnoreCase(v) || "-".equals(v) || "none".equalsIgnoreCase(v)) {
            return "";
        }
        return v;
    }

    public String threads() {
        return threads;
    }

    public void threads(String v) {
        threads = v == null ? "" : v.trim();
    }

    public String dataDir() {
        return dataDir;
    }

    public void dataDir(String v) {
        dataDir = v == null || v.isEmpty() ? defaultDataDir().getAbsolutePath() : v;
    }

    /** 解析 {@code dataDir}，包括开头的 {@code ~}。 */
    public File dataDirFile() {
        String d = dataDir == null ? "" : dataDir.trim();
        if (d.startsWith("~")) {
            d = System.getProperty("user.home") + d.substring(1);
        }
        return d.isEmpty() ? defaultDataDir() : new File(d);
    }

    // 辅助方法

    private static String str(Map<String, Object> m, String k, String dflt) {
        Object v = m.get(k);
        return v instanceof String ? (String) v : dflt;
    }

    private static boolean bool(Map<String, Object> m, String k, boolean dflt) {
        Object v = m.get(k);
        return v instanceof Boolean ? ((Boolean) v).booleanValue() : dflt;
    }
}
