package cn.dmego.dev.depviz.http;

import cn.dmego.dev.depviz.scan.Canceller;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/** 一次扫描任务。 */
public final class Job {

    /** 日志保留上限：前端只增量拉，多了没意义还占内存。 */
    private static final int MAX_LOG_LINES = 4000;

    public final String id;
    public volatile String projectKey;
    public final Deque<String> log = new ArrayDeque<String>();
    public final Canceller canceller = new Canceller();
    public volatile String status = "running";
    public volatile String stage = "discover";
    public volatile String error;
    public volatile String errorCode;
    /**
     * 本次扫描的完整 Maven 日志落在哪个文件（{@code data/<项目名>/maven.log}）。
     *
     * <p>内存里的日志只留最后 4000 行、进程一退就没了，失败时前端还要把它塞进
     * 一个折叠面板里。给用户一个能直接打开的路径，才谈得上「事后分析」。
     * 落盘没成功（数据目录不可写）时为 {@code null}，前端据此隐藏这一行。
     */
    public volatile String logFile;
    public volatile List<String> warnings = Collections.emptyList();
    public volatile double progress = 0.05;
    public volatile long startedAt = System.currentTimeMillis();
    public volatile long finishedAt;
    public volatile int logTotal;

    Job(String id, String projectKey) {
        this.id = id;
        this.projectKey = projectKey;
    }

    public synchronized void addLog(String line) {
        log.addLast(line);
        logTotal++;
        while (log.size() > MAX_LOG_LINES) {
            log.removeFirst();
        }
    }

    /** 从绝对行号 {@code offset} 开始取，最多 {@code max} 行。 */
    public synchronized List<String> linesSince(int offset, int max) {
        int firstAvailable = logTotal - log.size();
        int from = Math.max(0, offset - firstAvailable);
        List<String> out = new ArrayList<String>(Math.min(max, log.size()));
        int i = 0;
        for (String s : log) {
            if (i++ < from) {
                continue;
            }
            if (out.size() >= max) {
                break;
            }
            out.add(s);
        }
        return out;
    }

    /**
     * 日志的最后 {@code max} 行，拼成一段文本。
     *
     * <p>用于错误响应的 {@code detail}：Maven 的失败原因（{@code Non-parseable POM}、
     * {@code Could not resolve dependencies}……）只出现在日志里，全塞进 {@code message}
     * 会让前端的 toast 撑爆一屏，塞进 {@code detail} 才能「toast 一行 + 详情可展开」。
     */
    public synchronized String tailText(int max) {
        List<String> all = new ArrayList<String>(log);
        int from = Math.max(0, all.size() - max);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < all.size(); i++) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(all.get(i));
        }
        return sb.toString();
    }

    public long elapsedMs() {
        long end = "running".equals(status) ? System.currentTimeMillis() : finishedAt;
        return end - startedAt;
    }
}

