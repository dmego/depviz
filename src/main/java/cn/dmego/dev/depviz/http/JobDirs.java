package cn.dmego.dev.depviz.http;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/** 每次扫描独占一个目录；目录内的文件锁表示任务仍在使用它。 */
public final class JobDirs {

    private static final Map<File, Lease> active = new HashMap<File, Lease>();
    private static final String ACTIVE_LOCK = ".active";

    private JobDirs() {
    }

    /**
     * 创建跨进程唯一的目录，并持有它的使用锁，直到 {@link #finish(File)}。
     * 创建和启动清理共用一把短期文件锁，避免目录刚创建、尚未加锁时被当成遗留目录删除。
     */
    static synchronized File newJobDir(File dataDir) {
        if (dataDir == null) {
            return null;
        }
        File dir = null;
        try (Lease gate = gate(dataDir)) {
            File jobs = new File(dataDir, "jobs");
            Files.createDirectories(jobs.toPath());
            dir = Files.createTempDirectory(jobs.toPath(), "scan-").toFile().getCanonicalFile();
            Lease lease = Lease.acquire(new File(dir, ACTIVE_LOCK), false);
            active.put(dir, lease);
            return dir;
        } catch (IOException | OverlappingFileLockException e) {
            FileUtil.deleteRecursively(dir);
            return null;
        }
    }

    /** 只回收本进程创建且仍持有的任务目录，不能删除其他任务的路径。 */
    static synchronized void finish(File dir) {
        if (dir == null) {
            return;
        }
        Lease lease = active.remove(dir);
        if (lease == null) {
            return;
        }
        try (Lease gate = gate(dir.getParentFile().getParentFile())) {
            lease.close();
            FileUtil.deleteRecursively(dir);
        } catch (IOException e) {
            // 释放使用锁后，下一次启动可以回收。绝不能泄漏句柄或锁。
            lease.close();
        }
    }

    /**
     * 清理崩溃遗留的目录。每个目录独立判断存活，实例 A 退出不影响仍运行的实例 B；
     * 不再用「第一个实例持有全局锁」推断所有其他实例都已退出。
     */
    public static synchronized void cleanStale(File dataDir) {
        if (dataDir == null) {
            return;
        }
        try (Lease gate = gate(dataDir)) {
            File[] dirs = new File(dataDir, "jobs").listFiles();
            if (dirs == null) {
                return;
            }
            for (File dir : dirs) {
                if (!dir.getName().startsWith("scan-") || !dir.isDirectory()
                        || Files.isSymbolicLink(dir.toPath())) {
                    continue;
                }
                try {
                    Lease lease = Lease.acquire(new File(dir, ACTIVE_LOCK), true);
                    if (lease != null) {
                        // Windows 不能删除打开的锁文件；gate 确保关锁后没有新任务认领此目录。
                        lease.close();
                        FileUtil.deleteRecursively(dir);
                    }
                } catch (IOException | OverlappingFileLockException busy) {
                    // 同 JVM 的重叠锁或别的进程仍占用：保留活跃目录。
                }
            }
        } catch (IOException e) {
            // 只读或无权检查时留到下次，不能猜测目录是否正在使用。
        }
    }

    private static Lease gate(File dataDir) throws IOException {
        Files.createDirectories(dataDir.toPath());
        return Lease.acquire(new File(dataDir, ".jobs.lock"), false);
    }

    private static final class Lease implements AutoCloseable {
        private final RandomAccessFile file;
        private final FileLock lock;

        private Lease(RandomAccessFile file, FileLock lock) {
            this.file = file;
            this.lock = lock;
        }

        static Lease acquire(File path, boolean nonBlocking) throws IOException {
            RandomAccessFile file = new RandomAccessFile(path, "rw");
            try {
                FileLock lock = nonBlocking ? file.getChannel().tryLock() : file.getChannel().lock();
                if (lock != null) {
                    return new Lease(file, lock);
                }
                file.close();
                return null;
            } catch (IOException | RuntimeException e) {
                file.close();
                throw e;
            }
        }

        @Override
        public void close() {
            try {
                if (lock.isValid()) {
                    lock.release();
                }
            } catch (IOException ignored) {
                // close 也会释放操作系统锁。
            } finally {
                try {
                    file.close();
                } catch (IOException ignored) {
                    // 清理失败不改变扫描结果。
                }
            }
        }
    }
}
