package cn.dmego.dev.depviz.http;

/**
 * 一个「打开过」的项目。{@code key} 是根目录绝对路径（`02 §6.5.2` 钉死的形状）。
 *
 * <p>项目状态只有两样东西：<b>元数据</b>（名字、模块数、打开时间）与<b>一份图</b>。
 * 图以 {@link Payload} 的整体形式发布 —— 要么还没有（没扫过），要么是完整的一代。
 * 不缓存内存里的 {@code Graph}：服务端不做任何图查询（决策 D9），解析出来的对象
 * 除了序列化那一次之外没有用处。
 */
public final class ProjectState {

    public final String key;
    public final String name;
    public final long when;
    /**
     * 模块数。来自扫描时的发现结果；重启恢复时来自 {@code projects.json}，
     * 与图里的 {@code modules[]} 长度一致。
     */
    public final int moduleCount;

    /** 当前这一代图。{@code null} = 还没扫过（接口回 404）。整体替换，不可变。 */
    public volatile Payload payload;

    ProjectState(String key, String name, long when, int moduleCount, Payload payload) {
        this.key = key;
        this.name = name;
        this.when = when;
        this.moduleCount = moduleCount;
        this.payload = payload;
    }

    /**
     * 一代图：原文、UTF-8 字节、gzip 字节，以及同一模块编号对应的目录和实际 POM 路径。
     *
     * <p><b>为什么打包成一个不可变对象整体发布</b>：{@code /api/graph} 与并发的重扫会
     * 同时发生，分开的字段会让读取方看到「json 已就绪、bytes 还是 null」的中间态，
     * 随后 {@code Gzip.maybe} 直接 NPE → 500。
     *
     * <p>{@code relPaths} 与 {@code pomPaths} 从同一代图计算并恢复：`/api/pom` 按模块编号
     * 查实际文件，不猜文件名。两份映射只在发布或恢复时计算，请求时直接按下标读取。
     */
    public static final class Payload {
        public final String json;
        public final byte[] bytes;
        public final byte[] gzip;
        /** 下标 = 模块编号（{@code occ[].m}），空串表示「不知道这个模块在哪」。 */
        public final String[] relPaths;
        /** 下标同模块编号，值是相对项目根的实际 POM 文件路径；空串表示缺少身份。 */
        public final String[] pomPaths;

        Payload(String json, byte[] bytes, byte[] gzip, String[] relPaths, String[] pomPaths) {
            this.json = json;
            this.bytes = bytes;
            this.gzip = gzip;
            this.relPaths = relPaths;
            this.pomPaths = pomPaths;
        }
    }
}
