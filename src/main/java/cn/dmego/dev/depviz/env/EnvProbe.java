package cn.dmego.dev.depviz.env;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 探测运行环境。只做「读版本号」这一件事，坏掉了也不能影响扫描。
 *
 * <p>元数据条要显示 Maven / JDK / 插件版本（`views.js:879-893` 硬依赖 `p.maven` /
 * `p.jdk` / `p.plugin`），这三个字段缺一个前端就渲染 `undefined`。
 */
public final class EnvProbe {

    private static final int PROBE_TIMEOUT_SECONDS = 20;

    private EnvProbe() {
    }

    /**
     * {@code mvn -v} 的第一行里取版本；取不到返回空串。
     *
     * <p>名字先走 {@link MavenExecutable#resolve(String)}：Windows 上配成 {@code ...\bin\mvn}
     * 时，这里如果直接执行同名文件，会跟扫描一样报 error=193，只是错误被吞成空串，
     * 表现为元数据条上 Maven 版本一栏无缘无故是空的。
     */
    public static String mavenVersion(String mvnExecutable) {
        String out = exec(MavenExecutable.resolve(mvnExecutable), "-v");
        // Apache Maven 3.9.16 (bb17869d8ab77...)
        int i = out.indexOf("Apache Maven ");
        if (i < 0) {
            return "";
        }
        String rest = out.substring(i + "Apache Maven ".length()).trim();
        int sp = rest.indexOf(' ');
        return sp < 0 ? rest : rest.substring(0, sp);
    }

    /** {@code java -version} 输出是 stderr；取引号里的版本号。 */
    public static String javaVersion(String javaHome) {
        String out = exec(javaExecutable(javaHome), "-version");
        int a = out.indexOf('"');
        int b = out.indexOf('"', a + 1);
        if (a >= 0 && b > a) {
            return out.substring(a + 1, b);
        }
        return System.getProperty("java.version", "");
    }

    public static String javaHome() {
        return System.getProperty("java.home", "");
    }

    /**
     * 按物理内存总量选 Maven 子进程的 {@code -Xmx}。
     *
     * <p>档位：< 1 GB 不注入；1–1.5 G → 384m；1.5–2 G → 512m；2–4 G → 768m；> 4 G → 1024m。
     */
    public static String xmxFor(long physicalBytes) {
        long gb = physicalBytes / (1024L * 1024L * 1024L);
        if (gb < 1) {
            return "";
        }
        if (gb < 2) {
            return physicalBytes < (long) (1.5 * 1024 * 1024 * 1024) ? "384m" : "512m";
        }
        if (gb < 4) {
            return "768m";
        }
        return "1024m";
    }

    /**
     * 本机可给 Maven 的堆上限。
     *
     * <p>必须读**物理内存**，不能读 {@code Runtime.maxMemory()} —— 那是本 JVM 自己的堆上限
     * （启动时按物理内存 1/4 算），拿它当「可用内存」会系统性低估四倍。
     * 取不到时返回 512m（计划的兜底值：探测失败也绝不能不给 -Xmx）。
     */
    public static String xmxForThisMachine() {
        Long total = physicalMemoryBytes();
        return total == null ? "512m" : xmxFor(total);
    }

    /** 物理内存；取不到返回 {@code null}（调用方用 512m 兜底）。 */
    static Long physicalMemoryBytes() {
        try {
            java.lang.management.OperatingSystemMXBean os =
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            java.lang.reflect.Method m = os.getClass()
                    .getMethod("getTotalPhysicalMemorySize");
            Object v = m.invoke(os);
            return v instanceof Number ? Long.valueOf(((Number) v).longValue()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String javaExecutable(String javaHome) {
        String home = javaHome;
        if (home == null || home.isEmpty()) {
            home = System.getenv("JAVA_HOME");
        }
        if (home == null || home.isEmpty()) {
            home = System.getProperty("java.home", "");
        }
        if (home.isEmpty()) {
            return "java";
        }
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe" : "java";
        return new File(new File(home, "bin"), executable).getAbsolutePath();
    }

    /**
     * 跑一个短命令并收集输出，{@value #PROBE_TIMEOUT_SECONDS} 秒后放弃。
     *
     * <p>读流必须另起线程。早先是「先 readLine 到 EOF，再 waitFor(timeout)」——
     * 看起来有超时，实际上 {@code readLine()} 会一直阻塞到进程关掉 stdout，
     * 进程要是既不输出也不退出（JAVA_HOME 指到一个会挂住的包装脚本），
     * 这个探测就把调用方永远钉在这里，超时那一行根本执行不到。
     */
    private static String exec(String... cmd) {
        Process p;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            p = pb.start();
        } catch (IOException e) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Thread reader = new Thread(() -> {
            try {
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                try {
                    String line;
                    while ((line = r.readLine()) != null && sb.length() < 4096) {
                        sb.append(line).append('\n');
                    }
                } finally {
                    r.close();
                }
            } catch (IOException ignored) {
                // 进程被杀时流会异常关闭，正常路径
            }
        }, "env-probe");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!p.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "";
            }
            reader.join(2000);
            return sb.toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            return "";
        }
    }
}
