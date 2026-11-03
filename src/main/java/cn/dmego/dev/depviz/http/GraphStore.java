package cn.dmego.dev.depviz.http;

import cn.dmego.dev.depviz.log.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** 按项目归属管理扫描图数据，并通过临时文件原子写入。 */
final class GraphStore {

    static final String FILE_NAME = "graph.json";
    private static final String OWNER_FILE = ".owner";
    /** 写入中临时文件的后缀。 */
    private static final String TMP_SUFFIX = ".tmp";
    /** 认领目录时允许存在的扫描产物。 */
    private static final String SCAN_LOG = ScanLog.FILE_NAME;
    private static final String TREE = "tree.txt";

    private final File dataDir;

    GraphStore(File dataDir) {
        this.dataDir = dataDir;
    }

    /** 返回属于 {@code key} 的项目目录；无法安全认领时返回 {@code null}。 */
    static File dirFor(File dataDir, String projectName, String key, boolean create) {
        String safe = safeName(projectName);
        File plain = new File(dataDir, safe);
        if (claim(plain, key, create)) {
            return plain;
        }
        File hashed = new File(dataDir, safe + '-' + hash8(key));
        return claim(hashed, key, create) ? hashed : null;
    }

    /** 原子创建 {@code .owner} 标记；返回目录是否属于 {@code key}。 */
    private static boolean claim(File dir, String key, boolean create) {
        File owner = new File(dir, OWNER_FILE);
        if (owner.isFile()) {
            return key.equals(readOwner(owner));
        }
        if (!create) {
            // 无法确认归属时，读取路径直接让路。
            return false;
        }
        if (dir.exists() && !dir.isDirectory()) {
            return false;
        }
        // 仅认领空目录或只含本程序产物的目录。
        if (dir.isDirectory() && !onlyOurs(dir)) {
            return false;
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return false;
        }
        try {
            Files.write(owner.toPath(), key.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return true;
        } catch (FileAlreadyExistsException e) {
            // 并发认领时仅接受相同的项目 key。
            return key.equals(readOwner(owner));
        } catch (IOException e) {
            return false;
        }
    }

    /** 检查目录是否只含本程序产物。 */
    private static boolean onlyOurs(File dir) {
        String[] kids = dir.list();
        if (kids == null) {
            return false;
        }
        for (String k : kids) {
            if (!FILE_NAME.equals(k) && !OWNER_FILE.equals(k) && !SCAN_LOG.equals(k)
                    && !TREE.equals(k) && !k.endsWith(TMP_SUFFIX)) {
                return false;
            }
        }
        return true;
    }

    private static String readOwner(File owner) {
        try {
            return new String(Files.readAllBytes(owner.toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            // 无法读取 owner 时按不匹配处理。
            return "";
        }
    }

    /** @return 落盘后的文件；写失败返回 {@code null}（不能因为存不下就让扫描失败） */
    File save(String projectName, String key, String json) {
        File dir = dirFor(dataDir, projectName, key, true);
        if (dir == null) {
            Log.warn("store", "没有可写的项目数据目录（dataDir=" + dataDir + "，项目=" + projectName + "）");
            return null;
        }
        File target = new File(dir, FILE_NAME);
        File tmp = null;
        try {
            // 每次使用独立临时文件，避免并发写入相互覆盖。
            tmp = File.createTempFile(FILE_NAME + "-", TMP_SUFFIX, dir);
            Files.write(tmp.toPath(), json.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            tmp = null;
            return target;
        } catch (IOException e) {
            Log.warn("store", "写项目数据失败 " + target + ": " + e.getMessage());
            return null;
        } finally {
            if (tmp != null && tmp.exists() && !tmp.delete()) {
                Log.warn("store", "临时文件清不掉（下次启动会当成垃圾）: " + tmp);
            }
        }
    }

    /** @return 磁盘上的图 JSON；没有该项目的文件返回 {@code null} */
    String load(String projectName, String key) {
        return load(dataDir, projectName, key);
    }

    /** 为扫描日志获取项目数据目录。 */
    File dirForProject(String projectName, String key, boolean create) {
        return dirFor(dataDir, projectName, key, create);
    }

    /** @return 磁盘上的图 JSON；没有该项目的文件返回 {@code null} */
    static String load(File dataDir, String projectName, String key) {
        File dir = dirFor(dataDir, projectName, key, false);
        if (dir == null) {
            return null;
        }
        File f = new File(dir, FILE_NAME);
        if (!f.isFile()) {
            return null;
        }
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.warn("store", "读项目数据失败 " + f + ": " + e.getMessage());
            return null;
        }
    }

    /** 将路径分隔符、冒号和空白替换为下划线。 */
    static String safeName(String name) {
        String s = name == null ? "" : name.trim();
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '\0' || Character.isWhitespace(c)) {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String out = sb.toString();
        if (out.isEmpty() || ".".equals(out) || "..".equals(out)) {
            return "project";
        }
        // 避免生成隐藏目录。
        return out.charAt(0) == '.' ? "_" + out.substring(1) : out;
    }

    private static String hash8(String key) {
        int h = key == null ? 0 : key.hashCode();
        return String.format("%08x", h);
    }
}
