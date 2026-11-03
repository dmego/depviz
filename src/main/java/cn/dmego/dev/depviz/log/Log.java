package cn.dmego.dev.depviz.log;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 轻量日志实现，不依赖第三方库。
 *
 * <p>控制台默认 {@code INFO}，文件输出可选；同步写文件以保留失败前的末尾日志，标签由调用方提供。
 *
 * <p>级别：{@code ERROR} 表示请求或扫描失败，{@code WARN} 表示可继续的问题，
 * {@code INFO} 记录启动、扫描和慢请求，{@code DEBUG} 记录请求与 Maven 输出行。
 */
public final class Log {

    /** 级别。顺序即严重度，{@link #log} 直接比较序号。 */
    public enum Level {
        DEBUG, INFO, WARN, ERROR;

        /** 解析级别；未识别时回落到 {@code INFO}。 */
        public static Level parse(String s) {
            if (s == null) {
                return INFO;
            }
            for (Level l : values()) {
                if (l.name().equalsIgnoreCase(s.trim())) {
                    return l;
                }
            }
            return INFO;
        }
    }

    private static final Object LOCK = new Object();
    private static final SimpleDateFormat STAMP = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    /** 超过上限时滚动为 {@code .1} 备份，并覆盖旧备份。 */
    private static final long MAX_FILE_BYTES = 8L * 1024 * 1024;

    /** 控制台与文件默认 {@code INFO}，避免常规状态请求淹没启动和错误信息。 */
    private static volatile Level consoleLevel = Level.INFO;
    private static volatile Level fileLevel = Level.INFO;
    /** 由 {@link #LOCK} 保护。volatile 是为了让 {@link #isDebug} 的无锁读取看到最新值。 */
    private static volatile PrintWriter file;
    private static File filePath;
    private static long written;

    private Log() {
    }

    /**
     * 以追加模式打开日志文件，按需创建父目录；相对文件名以当前目录为基准。
     *
     * @param f 目标日志文件
     * @return 成功时返回文件，无法打开时返回 {@code null}
     */
    public static File toFile(File f) {
        if (f == null) {
            return null;
        }
        File parent = f.getParentFile();
        if (parent == null) {
            parent = new File(".").getAbsoluteFile();
        }
        return toFile(parent, f.getName());
    }

    public static File toFile(File dir, String name) {
        if (dir == null || name == null || name.isEmpty()) {
            return null;
        }
        File f = new File(dir, name);
        // 在锁内创建并替换 writer，避免并发 toFile 调用覆盖未关闭的 writer。
        synchronized (LOCK) {
            try {
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    return null;
                }
                PrintWriter w = new PrintWriter(Files.newBufferedWriter(f.toPath(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND), true);
                closeLocked();
                file = w;
                filePath = f;
                written = f.length();
                return f;
            } catch (IOException e) {
                return null;
            }
        }
    }

    public static void consoleLevel(Level l) {
        consoleLevel = l == null ? Level.INFO : l;
    }

    public static void fileLevel(Level l) {
        fileLevel = l == null ? Level.INFO : l;
    }

    /** 测试与工具类用：关掉文件输出并复位级别，避免用例之间互相污染。 */
    public static void resetForTest() {
        synchronized (LOCK) {
            closeLocked();
        }
        consoleLevel = Level.INFO;
        fileLevel = Level.INFO;
    }

    public static void debug(String tag, String msg) {
        log(Level.DEBUG, tag, msg, null);
    }

    public static void info(String tag, String msg) {
        log(Level.INFO, tag, msg, null);
    }

    public static void warn(String tag, String msg) {
        log(Level.WARN, tag, msg, null);
    }

    public static void error(String tag, String msg) {
        log(Level.ERROR, tag, msg, null);
    }

    public static void error(String tag, String msg, Throwable t) {
        log(Level.ERROR, tag, msg, t);
    }

    /** 写一条日志；{@code WARN}/{@code ERROR} 输出到 {@code System.err}，其余输出到 {@code System.out}。 */
    public static void log(Level level, String tag, String msg, Throwable t) {
        String line = format(level, tag, msg);
        String trace = t == null ? null : stack(t);
        boolean toConsole = level.ordinal() >= consoleLevel.ordinal();
        synchronized (LOCK) {
            if (toConsole) {
                PrintStream s = level == Level.ERROR || level == Level.WARN ? System.err : System.out;
                s.println(line);
                if (trace != null) {
                    s.print(trace);
                }
                s.flush();
            }
            if (file != null && level.ordinal() >= fileLevel.ordinal()) {
                file.println(line);
                if (trace != null) {
                    file.print(trace);
                }
                file.flush();
                written += line.length() + 1 + (trace == null ? 0 : trace.length());
                if (written >= MAX_FILE_BYTES) {
                    rotateLocked();
                }
            }
        }
    }

    /** 供调用方避免为不需要的 Maven 调试行生成时间戳和字符串。 */
    public static boolean isDebug() {
        return Level.DEBUG.ordinal() >= consoleLevel.ordinal() || file != null;
    }

    /** 将当前文件滚动为 {@code <name>.1} 并重开；只在 {@link #LOCK} 内调用。 */
    private static void rotateLocked() {
        written = 0;
        if (filePath == null) {
            return;
        }
        closeLocked();
        File backup = new File(filePath.getParentFile(), filePath.getName() + ".1");
        try {
            Files.move(filePath.toPath(), backup.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // 忽略轮转失败，继续尝试打开日志文件。
        }
        try {
            file = new PrintWriter(Files.newBufferedWriter(filePath.toPath(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING), true);
        } catch (IOException e) {
            file = null;
        }
    }

    /** 关掉当前 writer。只在 {@link #LOCK} 内调用。 */
    private static void closeLocked() {
        if (file != null) {
            file.close();
            file = null;
        }
        written = 0;
    }

    private static String format(Level level, String tag, String msg) {
        String ts;
        synchronized (STAMP) {
            ts = STAMP.format(new Date());
        }
        return ts + ' ' + pad(level.name()) + " [" + Thread.currentThread().getName() + "] "
                + tag + " - " + msg;
    }

    private static String pad(String s) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < 5) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static String stack(Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }
}
