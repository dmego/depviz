package cn.dmego.dev.depviz.scan;

import java.io.File;

/** 一次 Maven 调用的结果。 */
public final class ScanOutcome {

    public enum Status { OK, FAILED, CANCELLED }

    public final Status status;
    public final File treeFile;
    public final String message;
    public final int logLines;
    /** Maven 输出里「缺 POM」的行数。>0 说明扫描缺依赖，前端据此变黄并给重试入口。 */
    public final int missingCount;

    private ScanOutcome(Status status, File treeFile, String message, int logLines, int missing) {
        this.status = status;
        this.treeFile = treeFile;
        this.message = message;
        this.logLines = logLines;
        this.missingCount = missing;
    }

    /** 成功。{@code logLines} 与 {@code missing} 都由 {@code MvnRunner.Drainer} 统计出来。 */
    public static ScanOutcome ok(File treeFile, MvnRunner.Drainer drainer) {
        return new ScanOutcome(Status.OK, treeFile, null, drainer.lines(), drainer.missing());
    }

    public static ScanOutcome failed(String message) {
        return new ScanOutcome(Status.FAILED, null, message, 0, 0);
    }

    public static ScanOutcome cancelled() {
        return new ScanOutcome(Status.CANCELLED, null, "扫描已取消", 0, 0);
    }

    public boolean isOk() {
        return status == Status.OK;
    }
}
