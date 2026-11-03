package cn.dmego.dev.depviz.discover;

/** 发现阶段的失败（找不到 pom、模块树为空、坐标读不出来）。 */
public class DiscoverException extends Exception {

    private static final long serialVersionUID = 1L;

    public DiscoverException(String message) {
        super(message);
    }

    public DiscoverException(String message, Throwable cause) {
        super(message, cause);
    }
}
