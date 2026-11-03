package cn.dmego.dev.depviz.http;

import java.io.File;

/** 临时目录清理。删不掉不报错 —— 这些只是扫描的中间产物。 */
final class FileUtil {

    private FileUtil() {
    }

    /**
     * 递归删除目录或文件。
     *
     * <p>守卫必须是 {@code !dir.exists()}，不能是 {@code !dir.isDirectory()} ——
     * 后者会把<b>普通文件</b>也短路掉，于是递归对文件无效、最后 {@code dir.delete()}
     * 因目录非空而失败，净效果是<b>一个文件都不删</b>（实测每次扫描泄漏 1.5 MB 的 tree.txt）。
     */
    static void deleteRecursively(File dir) {
        if (dir == null || !java.nio.file.Files.exists(dir.toPath(),
                java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (java.nio.file.Files.isDirectory(dir.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            File[] kids = dir.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    deleteRecursively(k);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
    }
}
