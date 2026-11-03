package cn.dmego.dev.depviz.http;

import cn.dmego.dev.depviz.config.ConfigStore;
import org.junit.Test;

import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 扫描的生命周期：并发准入、取消、状态轮询、参数规范化。
 *
 * <p>与 {@link RouterTest} 分开只是因为「文件 ≤300 行」—— 它们共享 {@link HttpTestBase}。
 */
public class RouterScanTest extends HttpTestBase {

    /** 两个并发扫描：第二个必须 409 SCAN_IN_PROGRESS（8GB 机器上两个 Maven 会打架）。 */
    @Test
    public void secondScanWhileRunningIs409() throws Exception {
        Resp first = send("POST", "/api/scan?dir=" + PROJ, null);
        assertEquals(200, first.status);
        String firstJob = jobId(first);

        Resp second = send("POST", "/api/scan?dir=" + PROJ, null);
        String secondJob = second.status == 200 ? jobId(second) : null;
        cancelAndWait(firstJob);
        if (secondJob != null) {
            cancelAndWait(secondJob);
        }
        assertEquals("并发扫描必须被拒", 409, second.status);
        assertTrue(second.body.contains("SCAN_IN_PROGRESS"));
    }

    /** 目录不存在 → 400 BAD_REQUEST，不是 500。 */
    @Test
    public void discoverMissingDirIs400() throws Exception {
        Resp r = send("GET", "/api/discover?dir=/nope/nothing", null);
        assertEquals(400, r.status);
        assertTrue(r.body.contains("BAD_REQUEST"));
    }

    /** /api/discover 的 modules[] 必须带 declared[]（确认页「声明依赖」的数据源）。 */
    @Test
    public void discoverIncludesDeclaredArray() throws Exception {
        Resp r = send("GET", "/api/discover?dir=" + PROJ, null);
        assertEquals(200, r.status);
        assertTrue("modules[] 缺 declared[]", r.body.contains("\"declared\":["));
    }

    /** 相对路径必须 400：服务进程的 user.dir 对用户不可见，dir=. 指向哪里全凭启动方式。 */
    @Test
    public void relativeDirIs400() throws Exception {
        Resp r = send("POST", "/api/scan?dir=.", null);
        assertEquals(400, r.status);
        assertTrue(r.body.contains("必须是绝对路径"));
    }

    /** 尾斜杠要被吃掉，否则同一项目会有两个 key（「最近打开」出现两条）。 */
    @Test
    public void trailingSlashIsCanonicalized() throws Exception {
        Resp r = send("POST", "/api/scan?dir=" + PROJ + "/", null);
        assertEquals(200, r.status);
        // 等扫描落盘后查 projectId
        String jobId = r.body.replaceAll(".*\"jobId\":\"([^\"]+)\".*", "$1");
        String pid = null;
        for (int i = 0; i < 600 && pid == null; i++) {
            Thread.sleep(100);
            Resp st = send("GET", "/api/scan/status?jobId=" + jobId, null);
            if (st.body.contains("\"status\":\"done\"")) {
                pid = st.body.replaceAll(".*\"projectId\":\"([^\"]+)\".*", "$1");
            } else if (st.body.contains("\"status\":\"failed\"")
                    || st.body.contains("\"status\":\"cancelled\"")) {
                assertTrue("扫描提前终止: " + st.body, false);
            }
        }
        assertNotNull("扫描在 60 秒内没有完成（pid 为 null）—— 是超时，不是尾斜杠的问题", pid);
        assertEquals("projectId 不能带尾斜杠", PROJ, pid);
    }

    /**
     * 建任务目录失败时不能留下一个永远 {@code running} 的幽灵 job。
     *
     * <p>早先 {@code newJob} 先登记 job、再建目录；只读数据目录时目录建失败、
     * 响应 500，但那个 job 一直「running」，{@code runningJob()} 于是把所有后续扫描
     * 都挡成 409 —— 用户改好配置也救不回来，只能重启进程。
     *
     * <p>现在 id 由 {@link cn.dmego.dev.depviz.http.Projects#nextJobId()} 领、目录建成功后才
     * {@code newJob(id, key)} 登记，所以「领了 id 但没登记」不会影响 runningJob。
     */
    @Test
    public void claimingAJobIdDoesNotMarkItRunning() {
        JobRegistry reg = new JobRegistry();
        String id = reg.nextJobId();
        assertTrue("id 要有 j 前缀", id.startsWith("j"));
        assertNull("只领 id 不占槽位，不能变成 running", reg.runningJob());

        Job first = reg.tryAcquire(id, "/x");
        assertNotNull("抢占成功返回新 job", first);
        assertNotNull(reg.runningJob());
        assertEquals("抢占者就是 running", id, reg.runningJob().id);

        // 第二个请求必须失败（返回 null），不能也抢到
        assertNull("第二次抢占必须失败", reg.tryAcquire(reg.nextJobId(), "/y"));
        assertEquals("挡路者仍是第一个", id, reg.runningJob().id);

        reg.finish(reg.runningJob());
        assertNull("结束后要放开，否则后续扫描全被 409 挡住", reg.runningJob());
    }

    /**
     * 建目录失败后的回滚：槽位必须释放，否则后续扫描永远 409。
     * （早先 newJob 先登记、后建目录，只读数据目录就是「500 之后再也扫不了」。）
     */
    @Test
    public void releasingASlotLetsTheNextScanThrough() {
        JobRegistry reg = new JobRegistry();
        Job held = reg.tryAcquire(reg.nextJobId(), "/x");
        assertNotNull(held);
        reg.release(held);
        assertNull("释放后槽位空出来", reg.runningJob());
        assertNotNull("下一个请求能抢到", reg.tryAcquire(reg.nextJobId(), "/y"));
    }

    /**
     * 重扫与读图并发时不能出现中间态。
     *
     * <p>一代图（json / bytes / gzip / relPaths）打包成不可变 {@code Payload} 一次发布，
     * 所以读取方要么看到旧的一整代、要么看到新的一整代。分成几个独立 volatile 字段
     * 依次写的话，{@code /api/graph} 会读到「json 已就绪、bytes 还是 null」，
     * 随后 {@code Gzip.maybe} 直接 NPE → 500。
     */
    @Test
    public void concurrentReadAndReplaceNeverSeesHalfAResult() throws Exception {
        final Projects projects = new Projects(null);
        final String key = "/tmp/x";
        final ProjectState state = new ProjectState(key, "x", 0L, 1, null);
        projects.putForTest(state);

        final java.util.concurrent.atomic.AtomicBoolean stop =
                new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicInteger nulls =
                new java.util.concurrent.atomic.AtomicInteger();
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!stop.get()) {
                    ProjectState.Payload p = projects.project(key).payload;
                    // payload 为 null 是合法的「还没扫过」（接口回 404）；
                    // 有 payload 却没有 bytes / relPaths 才是中间态。
                    if (p != null && (p.bytes == null || p.relPaths == null)) {
                        nulls.incrementAndGet();
                    }
                }
            }
        });
        reader.start();
        for (int i = 0; i < 2000; i++) {
            state.payload = new ProjectState.Payload("{}",
                    "{}".getBytes("UTF-8"), new byte[]{1, 2}, new String[0], new String[0]);
        }
        stop.set(true);
        reader.join(2000);
        assertEquals("读取方永远不该看到「有 json 没 bytes」的中间态", 0, nulls.get());
    }

    /**
     * 10 个线程同时 POST /api/scan，只能有一个拿到 200。
     *
     * <p>早先「检查 runningJob() → 建目录 → register()」是三步，中间约 1 ms 的窗口
     * 里并发请求会双双成功（threading.Barrier 实测 19/20 次双开，桩 maven 看到 4 个
     * 同时跑的进程）。现在 tryAcquire 用 CAS 抢占，这条测试钉住它。
     */
    @Test
    public void concurrentScansAdmitExactlyOne() throws Exception {
        final java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger ok =
                new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger rejected =
                new java.util.concurrent.atomic.AtomicInteger();
        final AtomicReference<String> acceptedJob = new AtomicReference<String>();
        java.util.List<Thread> ts = new java.util.ArrayList<Thread>();
        for (int i = 0; i < 10; i++) {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        gate.await();
                        Resp r = send("POST", "/api/scan?dir=" + PROJ, null);
                        if (r.status == 200) {
                            ok.incrementAndGet();
                            acceptedJob.set(jobId(r));
                        } else if (r.status == 409) {
                            rejected.incrementAndGet();
                        }
                    } catch (Throwable ignored) {
                        // 超时/连接错误也算「没抢到」
                    }
                }
            });
            t.start();
            ts.add(t);
        }
        gate.countDown();
        for (Thread t : ts) {
            t.join(15000);
        }
        if (acceptedJob.get() != null) {
            cancelAndWait(acceptedJob.get());
        }
        assertEquals("只能有一个扫描被接受", 1, ok.get());
        assertEquals("其余必须被 409 挡住", 9, rejected.get());
    }

    private String jobId(Resp response) {
        return response.body.replaceAll(".*\"jobId\":\"([^\"]+)\".*", "$1");
    }

    private void cancelAndWait(String jobId) throws Exception {
        for (int i = 0; i < 100; i++) {
            Resp status = send("GET", "/api/scan/status?jobId=" + jobId, null);
            if (status.body.contains("\"status\":\"done\"")
                    || status.body.contains("\"status\":\"failed\"")
                    || status.body.contains("\"status\":\"cancelled\"")) {
                return;
            }
            if (i == 0) {
                assertEquals(200, send("POST", "/api/scan/cancel?jobId=" + jobId, null).status);
            }
            Thread.sleep(50);
        }
        assertTrue("扫描未进入终态: " + jobId, false);
    }

    /**
     * job 表不能无限增长：每项最多留 4000 行日志（约 400 KB），连扫上百次就是几十 MB，
     * 而 TTL 只在新开扫描时才生效 —— 用户不扫了就永远不会清。
     */
    @Test
    public void jobTableKeepsAtMostTwentyAndNeverDropsTheRunningOne() throws Exception {
        JobRegistry reg = new JobRegistry();
        Job running = reg.tryAcquire(reg.nextJobId(), "/x");
        assertNotNull(running);
        reg.commit(running);
        for (int i = 0; i < 40; i++) {
            Job j = new Job(reg.nextJobId(), "/old" + i);
            j.finishedAt = System.currentTimeMillis() - (1000L * (100 - i));
            reg.commit(j);
        }
        assertNotNull("正在跑的那个绝不能被淘汰", reg.job(running.id));
        // 触发一次 prune（commit 里会调）；此时槽位被 running 占着，tryAcquire 会失败，
        // 所以直接 commit 一个新 job —— 它自己会走 prune
        reg.commit(new Job(reg.nextJobId(), "/tail"));
        assertTrue("表要有上限", reg.size() <= 20);
    }
}
