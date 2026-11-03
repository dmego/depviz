package cn.dmego.dev.depviz.scan;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 一次扫描要传给 Maven 的全部参数。 */
public final class ScanRequest {

    /** 默认超时。大项目在线解析可能几十秒，留足余量。 */
    public static final int DEFAULT_TIMEOUT_SECONDS = 600;

    /** 默认插件版本。3.6.0 是本机与目标机都验证过的那一个。 */
    public static final String DEFAULT_PLUGIN_VERSION = "3.6.0";

    public final File projectDir;
    public final File outputFile;
    public final boolean offline;
    public final List<String> profiles;
    public final String mavenExecutable;
    public final String javaHome;
    public final String settingsXml;
    public final String localRepo;
    public final String pluginVersion;
    public final String xmx;
    public final String threads;
    public final int timeoutSeconds;

    private ScanRequest(Builder b) {
        this.projectDir = b.projectDir;
        this.outputFile = b.outputFile;
        this.offline = b.offline;
        this.profiles = Collections.unmodifiableList(new ArrayList<String>(b.profiles));
        this.mavenExecutable = b.mavenExecutable;
        this.javaHome = b.javaHome;
        this.settingsXml = b.settingsXml;
        this.localRepo = b.localRepo;
        this.pluginVersion = b.pluginVersion;
        this.xmx = b.xmx;
        this.threads = b.threads;
        this.timeoutSeconds = b.timeoutSeconds;
    }

    /**
     * 真正会传给 Maven 的 {@code -T} 值；空串表示不加这个参数（串行）。
     *
     * <p>归一化规则住在 {@link MvnCommand#parallelFlag}：用户填的 {@code 1} / {@code off}
     * 语义上都是「不要并行」，命令行里干脆不出现 {@code -T}。元数据要展示的是
     * <b>本次实际生效的值</b>，所以展示与拼命令必须问同一个地方。
     */
    public String effectiveThreads() {
        return MvnCommand.parallelFlag(threads);
    }

    /** 以当前参数为模板开一个新 Builder。 */
    public Builder newBuilder() {
        Builder b = new Builder();
        b.projectDir = projectDir;
        b.outputFile = outputFile;
        b.offline = offline;
        b.profiles.addAll(profiles);
        b.mavenExecutable = mavenExecutable;
        b.javaHome = javaHome;
        b.settingsXml = settingsXml;
        b.localRepo = localRepo;
        b.pluginVersion = pluginVersion;
        b.xmx = xmx;
        b.threads = threads;
        b.timeoutSeconds = timeoutSeconds;
        return b;
    }

    public static final class Builder {
        private File projectDir;
        private File outputFile;
        /**
         * 默认<b>在线</b>，与 {@code ConfigStore} 的默认值一致。
         *
         * <p>离线只是「快」（3s vs 50s），代价是 BOM / build extension 解析不了的项目
         * 直接报 {@code Non-resolvable import POM}。默认取正确性。
         */
        private boolean offline;
        private final List<String> profiles = new ArrayList<String>();
        private String mavenExecutable = "mvn";
        private String javaHome;
        private String settingsXml;
        private String localRepo;
        private String pluginVersion = DEFAULT_PLUGIN_VERSION;
        private String xmx = "";
        private String threads = "";
        private int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;

        public Builder projectDir(File v) { this.projectDir = v; return this; }
        public Builder outputFile(File v) { this.outputFile = v; return this; }
        public Builder offline(boolean v) { this.offline = v; return this; }
        public Builder profile(String v) { if (v != null && !v.isEmpty()) { this.profiles.add(v); } return this; }
        public Builder mavenExecutable(String v) { if (v != null && !v.isEmpty()) { this.mavenExecutable = v; } return this; }
        public Builder javaHome(String v) { this.javaHome = v; return this; }
        public Builder settingsXml(String v) { this.settingsXml = v; return this; }
        public Builder localRepo(String v) { this.localRepo = v; return this; }
        public Builder pluginVersion(String v) { if (v != null && !v.isEmpty()) { this.pluginVersion = v; } return this; }
        /** 追加进 MAVEN_OPTS 的 {@code -Xmx}，空串表示不干预。 */
        public Builder xmx(String v) { this.xmx = v == null ? "" : v; return this; }
        /** 透传 {@code -T}；空串 / {@code 1} / {@code off} 表示串行（不加 {@code -T}）。 */
        public Builder threads(String v) { this.threads = v == null ? "" : v.trim(); return this; }
        public Builder timeoutSeconds(int v) { this.timeoutSeconds = v; return this; }

        public ScanRequest build() {
            if (projectDir == null) {
                throw new IllegalStateException("projectDir 必填");
            }
            if (outputFile == null) {
                throw new IllegalStateException("outputFile 必填");
            }
            return new ScanRequest(this);
        }
    }
}
