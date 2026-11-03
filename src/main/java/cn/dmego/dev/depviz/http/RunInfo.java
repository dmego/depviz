package cn.dmego.dev.depviz.http;

/**
 * 一次扫描结束时能拿到的运行信息。
 *
 * <p>用来代替 {@code metaOf(info, req, seconds, mavenVersion, jdkVersion, xmx)} 那个
 * 六参数签名 —— 六个位置参数里四个是字符串，调用处极容易传错顺序，而且编译期查不出来。
 */
public final class RunInfo {

    public final String mavenVersion;
    public final String jdkVersion;
    public final String xmx;
    public final double seconds;

    private RunInfo(String mavenVersion, String jdkVersion, String xmx, double seconds) {
        this.mavenVersion = mavenVersion;
        this.jdkVersion = jdkVersion;
        this.xmx = xmx;
        this.seconds = seconds;
    }

    /** 链式构造，避免四个位置参数传错顺序（三个都是 String，编译期查不出来）。 */
    public static final class Builder {
        private String mavenVersion = "";
        private String jdkVersion = "";
        private String xmx = "";
        private double seconds;

        public Builder mavenVersion(String v) { this.mavenVersion = v; return this; }

        public Builder jdkVersion(String v) { this.jdkVersion = v; return this; }

        public Builder xmx(String v) { this.xmx = v; return this; }

        public Builder seconds(double v) { this.seconds = v; return this; }

        public RunInfo build() {
            return new RunInfo(mavenVersion, jdkVersion, xmx, seconds);
        }
    }
}
