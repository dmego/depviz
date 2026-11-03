package cn.dmego.dev.depviz.desktop;

import java.awt.Desktop;
import java.net.URI;

/**
 * 启动后用系统默认浏览器打开本地页面。
 *
 * <p>三种情况都要能活：
 * <ul>
 *   <li>正常桌面环境：{@link Desktop#browse} 成功。</li>
 *   <li>无头环境（CI、SSH）：抛 {@link java.awt.HeadlessException}，打印 URL 让人自己点。</li>
 *   <li>{@code Desktop.isDesktopSupported() == false}（部分 Linux 精简发行版）：同样降级为打印。</li>
 * </ul>
 *
 * <p>用独立线程打开：某些 Linux 桌面上 {@code browse()} 会阻塞几百毫秒到几秒，
 * 不能挡住 HTTP 服务的启动。
 */
public final class BrowserLauncher {

    private BrowserLauncher() {
    }

    /** 异步打开浏览器；失败只打印，不抛。 */
    public static void open(final String url) {
        Thread t = new Thread(() -> tryOpen(url), "open-browser");
        t.setDaemon(true);
        t.start();
    }

    private static void tryOpen(String url) {
        try {
            if (!Desktop.isDesktopSupported()) {
                fallback(url);
                return;
            }
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.BROWSE)) {
                fallback(url);
                return;
            }
            desktop.browse(URI.create(url));
        } catch (Throwable t) {
            fallback(url);
        }
    }

    private static void fallback(String url) {
        System.out.println("请手动在浏览器打开: " + url);
    }
}
