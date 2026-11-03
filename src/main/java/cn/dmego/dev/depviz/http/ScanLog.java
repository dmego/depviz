package cn.dmego.dev.depviz.http;

import cn.dmego.dev.depviz.log.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** 保存单次扫描的 Maven 原始输出；超过上限后停止写入并记录说明。 */
final class ScanLog {

    static final String FILE_NAME = "maven.log";

    /** 单个扫描日志的字节上限。 */
    private static final long MAX_BYTES = 8L * 1024 * 1024;

    private final File file;
    private OutputStream out;
    private long written;
    private boolean truncated;

    private ScanLog(File file, OutputStream out) {
        this.file = file;
        this.out = out;
    }

    /** 打开并覆盖日志文件；打开失败时返回 {@code null}，不阻断扫描。 */
    static ScanLog open(File file) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("无法创建目录 " + parent);
            }
            OutputStream os = new BufferedOutputStream(new FileOutputStream(file, false));
            return new ScanLog(file, os);
        } catch (IOException | SecurityException e) {
            Log.warn("scanlog", "无法写扫描日志 " + file + ": " + e.getMessage());
            return null;
        }
    }

    File file() {
        return file;
    }

    /** 原样写一行（Maven 输出）。 */
    synchronized void line(String text) {
        write(text == null ? "" : text);
    }

    /** 写一行我们自己的说明，前缀 {@code === } 以便和 Maven 输出区分。 */
    synchronized void note(String text) {
        write("=== " + text);
    }

    private void write(String text) {
        if (out == null || truncated) {
            return;
        }
        byte[] bytes = (text + "\n").getBytes(StandardCharsets.UTF_8);
        if (written + bytes.length > MAX_BYTES) {
            truncated = true;
            writeRaw("=== 日志超过 " + (MAX_BYTES / 1024 / 1024) + " MB，后续输出已省略\n");
            closeQuietly();
            return;
        }
        try {
            // 逐行刷盘，保留进程被终止前的日志。
            out.write(bytes);
            out.flush();
            written += bytes.length;
        } catch (IOException e) {
            Log.warn("scanlog", "写扫描日志失败 " + file + ": " + e.getMessage());
            closeQuietly();
        }
    }

    private void writeRaw(String text) {
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            Log.warn("scanlog", "写扫描日志失败 " + file + ": " + e.getMessage());
        }
    }

    synchronized void close() {
        closeQuietly();
    }

    private void closeQuietly() {
        if (out == null) {
            return;
        }
        try {
            out.close();
        } catch (IOException e) {
            Log.warn("scanlog", "关闭扫描日志失败 " + file + ": " + e.getMessage());
        } finally {
            out = null;
        }
    }
}
