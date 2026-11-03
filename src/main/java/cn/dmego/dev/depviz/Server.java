package cn.dmego.dev.depviz;

/**
 * 本地 Web 服务入口占位。真正的实现在 {@code cn.dmego.dev.depviz.http.HttpService}，
 * 这里只保留 {@link Main} 需要的稳定入口。
 */
public final class Server {

    private Server() {
    }

    public static void main(String[] args) throws Exception {
        cn.dmego.dev.depviz.http.HttpService.main(args);
    }
}
