package cn.dmego.dev.depviz.http;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** 管理扫描作业、状态查询、历史回收和单扫描并发限制。 */
final class JobRegistry {

    /** 已完成任务的保留时长。 */
    private static final long JOB_TTL_MS = 10 * 60 * 1000L;

    /** 作业表大小上限，包含正在运行的任务。 */
    private static final int MAX_JOBS = 20;

    private final Map<String, Job> jobs = new ConcurrentHashMap<String, Job>();
    private final AtomicInteger seq = new AtomicInteger();

    /** 当前扫描槽位；CAS 保证同时最多一个任务。 */
    private final java.util.concurrent.atomic.AtomicReference<Job> running =
            new java.util.concurrent.atomic.AtomicReference<Job>();

    /** 正在跑的 job；没有则 {@code null}。 */
    Job runningJob() {
        Job j = running.get();
        return j != null && "running".equals(j.status) ? j : null;
    }

    /** 发一个进程内自增的 job id。只是句柄，不是全局唯一 id，不要持久化。 */
    String nextJobId() {
        return "j" + seq.incrementAndGet();
    }

    /** 当前表里的 job 数（测试与诊断用）。 */
    int size() {
        return jobs.size();
    }

    /** 把这个 job 记进历史表（{@link #tryAcquire} 只占槽位，登记在这里）。 */
    void commit(Job j) {
        jobs.put(j.id, j);
        prune();
    }

    /** 原子占用扫描槽位；已占用时返回 {@code null}。 */
    Job tryAcquire(String jobId, String projectKey) {
        prune();
        Job j = new Job(jobId, projectKey);
        Job busy = running.get();
        if (busy != null && "running".equals(busy.status)) {
            return null;
        }
        // CAS 失败表示另一个请求已占用槽位。
        return running.compareAndSet(busy, j) ? j : null;
    }

    /** 启动失败或取消时释放槽位，并移除未登记的任务。 */
    void release(Job j) {
        running.compareAndSet(j, null);
        jobs.remove(j.id);
    }

    Job job(String id) {
        return jobs.get(id);
    }

    /** 结束任务，并仅在槽位仍由该任务持有时释放。 */
    void finish(Job j) {
        running.compareAndSet(j, null);
    }

    /** 回收超过保留时长的已完成任务，并限制历史任务数量。 */
    private void prune() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Job>> it = jobs.entrySet().iterator();
        while (it.hasNext()) {
            Job j = it.next().getValue();
            if (j.finishedAt > 0 && now - j.finishedAt > JOB_TTL_MS) {
                it.remove();
            }
        }
        // 超出容量时淘汰最旧的已完成任务，保留运行中的任务。
        while (jobs.size() > MAX_JOBS) {
            Job oldest = null;
            for (Job j : jobs.values()) {
                if (j.finishedAt > 0 && (oldest == null || j.finishedAt < oldest.finishedAt)) {
                    oldest = j;
                }
            }
            if (oldest == null) {
                return;
            }
            jobs.remove(oldest.id);
        }
    }
}
