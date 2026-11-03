package cn.dmego.dev.depviz.env;

import java.io.File;
import java.util.Locale;

/**
 * 将 Maven 名称或路径解析为可执行文件。
 *
 * <p>Windows 上无扩展名的 {@code mvn} 可能是 shell 脚本；解析为 {@code .cmd/.bat/.exe}，
 * 由 JDK 处理脚本启动。不要手动拼 {@code cmd /c}，参数含引号时可能触发 embedded quote 错误。
 *
 * <p>非 Windows 保留 {@code mvn} 脚本名；若配置的是安装目录，则在其 {@code bin/} 中查找。
 */
public final class MavenExecutable {

    /** 没配置时用的名字。与 {@code ScanRequest.Builder} 的默认值保持一致。 */
    public static final String DEFAULT_NAME = "mvn";

    /** Windows 上依次尝试的脚本名后缀（Maven 3 用 .cmd，Maven 2 用 .bat）。 */
    private static final String[] CANDIDATE_SUFFIXES = {".cmd", ".bat", ".exe"};

    /** Maven 安装目录里，可执行文件所在的那一层。 */
    private static final String BIN_DIR = "bin";

    private MavenExecutable() {
    }

    /**
     * @param configured 用户配置的名字或绝对路径，可为空（空表示用 PATH 里的 mvn）
     * @return 可以直接交给 {@code ProcessBuilder} 的字符串
     */
    public static String resolve(String configured) {
        return resolve(configured, isWindows(), System.getenv("PATH"));
    }

    /** 使用与 {@code EnvProbe.javaExecutable} 相同的 Windows 判据。 */
    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 使用显式平台和 PATH 输入执行解析，便于在非 Windows 环境处理 Windows 规则。
     *
     * @param windows 是否按 Windows 规则解析
     * @param pathEnv 裸名称的 PATH 搜索值，可为 null
     */
    static String resolve(String configured, boolean windows, String pathEnv) {
        String raw = configured == null ? "" : configured.trim();
        if (raw.isEmpty()) {
            raw = DEFAULT_NAME;
        }
        String insideDir = mavenInside(raw, windows);
        if (insideDir != null) {
            return insideDir;
        }
        if (!windows) {
            return raw;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        for (String suffix : CANDIDATE_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return raw;
            }
        }
        if (hasDirectory(raw)) {
            // 有目录时，检查同路径下带后缀的可执行文件。
            String found = firstExistingBeside(raw);
            // 未找到时保留用户配置的路径，便于 ProcessBuilder 报错定位。
            return found == null ? raw : found;
        }
        // 裸名称需显式查 PATH：ProcessBuilder 只自动补 .exe，不会查找 .cmd/.bat。
        String onPath = firstOnPath(raw, pathEnv);
        return onPath == null ? raw + ".cmd" : onPath;
    }

    /**
     * 若配置指向 Maven 安装目录或其 {@code bin/}，就在 {@code bin/} 和当前目录查找可执行文件。
     *
     * @return 找到的 Maven 可执行文件；非目录或未找到时返回 null
     */
    private static String mavenInside(String raw, boolean windows) {
        File dir = new File(raw);
        if (!dir.isDirectory()) {
            return null;
        }
        // 先查安装目录下的 bin/，再查当前目录（配置可能已指向 bin/）。
        String[] layers = {BIN_DIR, ""};
        for (String layer : layers) {
            File base = layer.isEmpty() ? dir : new File(dir, layer);
            String found = findMvn(base, windows);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** 在 {@code dir} 里找一个 mvn 可执行文件。 */
    private static String findMvn(File dir, boolean windows) {
        if (!windows) {
            File plain = new File(dir, DEFAULT_NAME);
            return plain.isFile() ? plain.getAbsolutePath() : null;
        }
        for (String suffix : CANDIDATE_SUFFIXES) {
            File candidate = new File(dir, DEFAULT_NAME + suffix);
            if (candidate.isFile()) {
                return candidate.getAbsolutePath();
            }
        }
        return null;
    }

    /** 判断配置路径是否包含父目录。 */
    private static boolean hasDirectory(String raw) {
        File self = new File(raw);
        return self.getParentFile() != null;
    }

    /**
     * @return {@code raw} 同级第一个存在的 {@code .cmd/.bat/.exe}，一个都不存在返回 null
     */
    private static String firstExistingBeside(String raw) {
        for (String suffix : CANDIDATE_SUFFIXES) {
            File candidate = new File(raw + suffix);
            if (candidate.isFile()) {
                return candidate.getAbsolutePath();
            }
        }
        return null;
    }

    /** 按 PATH 顺序搜索 {@code name.cmd}、{@code name.bat}、{@code name.exe}。 */
    private static String firstOnPath(String name, String pathEnv) {
        if (pathEnv == null || pathEnv.isEmpty()) {
            return null;
        }
        for (String entry : pathEnv.split(File.pathSeparator)) {
            String dir = entry.trim();
            if (dir.isEmpty()) {
                continue;
            }
            String found = firstExistingBeside(new File(dir, name).getPath());
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
