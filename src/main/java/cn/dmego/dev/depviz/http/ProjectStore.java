package cn.dmego.dev.depviz.http;

import cn.dmego.dev.depviz.config.ConfigStore;
import cn.dmego.dev.depviz.config.JsonIn;
import cn.dmego.dev.depviz.json.JsonOut;
import cn.dmego.dev.depviz.log.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 最近项目的键级事务存储；文件锁覆盖读取、合并和原子提交。 */
final class ProjectStore {

    // 同 JVM 内先排队，避免 FileChannel.lock 对重叠锁直接抛异常。
    private static final Object LOCK = new Object();
    private final File file;

    ProjectStore(File dataDir) {
        this.file = new File(dataDir, "projects.json").getAbsoluteFile();
    }

    static ProjectStore forConfig(ConfigStore config) {
        return new ProjectStore(config.dataDirFile());
    }

    /** 原子 rename 保证读者只能读到某一次完整提交。缺失或无效文件不阻止启动。 */
    List<Record> load() {
        List<Record> out = new ArrayList<Record>();
        if (!file.isFile()) {
            return out;
        }
        try {
            Object parsed = JsonIn.parse(new String(Files.readAllBytes(file.toPath()),
                    StandardCharsets.UTF_8));
            if (parsed instanceof List) {
                for (Object item : (List<?>) parsed) {
                    Record r = Record.of(item);
                    if (r != null) {
                        out.add(r);
                    }
                }
            }
        } catch (Exception e) {
            Log.warn("store", "无法读取最近项目列表 " + file + ": " + e.getMessage());
            out.clear();
        }
        return out;
    }

    void put(Record record) {
        update(record.key, record);
    }

    void remove(String key) {
        update(key, null);
    }

    private void update(String key, Record replacement) {
        synchronized (LOCK) {
            try {
                File dir = file.getParentFile();
                Files.createDirectories(dir.toPath());
                try (RandomAccessFile lockFile = new RandomAccessFile(
                        new File(dir, ".projects.lock"), "rw");
                     FileLock ignored = lockFile.getChannel().lock()) {
                    Map<String, Record> records = new LinkedHashMap<String, Record>();
                    for (Record record : load()) {
                        records.put(record.key, record);
                    }
                    if (replacement == null) {
                        records.remove(key);
                    } else {
                        records.put(key, replacement);
                    }
                    List<Record> ordered = new ArrayList<Record>(records.values());
                    Collections.sort(ordered, (a, b) -> Long.compare(b.when, a.when));
                    write(ordered);
                }
            } catch (IOException e) {
                Log.warn("store", "无法保存最近项目列表 " + file + ": " + e.getMessage());
            }
        }
    }

    private void write(List<Record> records) throws IOException {
        JsonOut o = JsonOut.pretty();
        o.beginArray();
        for (Record r : records) {
            o.beginObject();
            o.field("key", r.key);
            o.field("name", r.name);
            o.field("moduleCount", r.moduleCount);
            o.field("when", r.when);
            o.field("rootDir", r.rootDir);
            o.endObject();
        }
        o.endArray();
        Path temp = Files.createTempFile(file.getParentFile().toPath(), "projects-", ".tmp");
        try {
            Files.write(temp, o.toString().getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static final class Record {
        final String key;
        final String name;
        final int moduleCount;
        final long when;
        final String rootDir;

        Record(String key, String name, int moduleCount, long when, String rootDir) {
            this.key = key;
            this.name = name;
            this.moduleCount = moduleCount;
            this.when = when;
            this.rootDir = rootDir == null || rootDir.isEmpty() ? key : rootDir;
        }

        static Record of(Object item) {
            if (!(item instanceof Map)) {
                return null;
            }
            Map<?, ?> m = (Map<?, ?>) item;
            String key = str(m.get("key"));
            if (key.isEmpty()) {
                return null;
            }
            String name = str(m.get("name"));
            return new Record(key, name.isEmpty() ? new File(key).getName() : name,
                    intOf(m.get("moduleCount")), longOf(m.get("when")), str(m.get("rootDir")));
        }

        private static String str(Object v) {
            return v instanceof String ? (String) v : "";
        }

        private static int intOf(Object v) {
            return v instanceof Number ? ((Number) v).intValue() : 0;
        }

        private static long longOf(Object v) {
            return v instanceof Number ? ((Number) v).longValue() : System.currentTimeMillis();
        }
    }
}
