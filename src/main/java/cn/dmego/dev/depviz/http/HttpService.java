package cn.dmego.dev.depviz.http;

import com.sun.net.httpserver.HttpServer;
import cn.dmego.dev.depviz.log.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * 本地 HTTP 服务。
 *
 * <p>安全与并发要求：
 * <ul>
 *   <li>只绑 <b>回环地址</b>，不绑 0.0.0.0 —— 否则同一局域网的人都能扫你的机器。</li>
 *   <li>校验 {@code Host} 与 {@code Origin}，不对就 403 —— 防 DNS rebinding。</li>
 *   <li>必须 {@code setExecutor}，默认线程池是单线程，一个慢扫描会把页面卡死。</li>
 * </ul>
 */
public final class HttpService {

    /** 默认端口。被占用就往后试，最多 {@link #PORT_TRIES} 个。 */
    private static final int DEFAULT_PORT = 8080;
    private static final int PORT_TRIES = 10;

    private final HttpServer server;
    private final int port;
    /** 退出时要用它找正在跑的扫描，见 {@link #installShutdownHook(HttpService)}。 */
    private final Router router;

    public HttpService(int port, Router router) throws IOException {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        this.server = HttpServer.create(new InetSocketAddress(loopback, port), 0);
        this.server.setExecutor(Executors.newCachedThreadPool());
        final int actualPort = this.server.getAddress().getPort();
        this.port = actualPort;
        this.router = router;
        this.server.createContext("/", ex -> {
            Exchange e = new Exchange(ex);
            if (!Guard.allowed(e, actualPort)) {
                Log.warn("http", "拒绝非本机请求: " + e.method() + " " + e.path());
                e.error(403, "FORBIDDEN", "只接受来自本机页面的请求");
                return;
            }
            long t0 = System.currentTimeMillis();
            try {
                router.handle(e);
            } catch (RuntimeException err) {
                Log.error("http", "未捕获的异常 " + e.method() + " " + e.path(), err);
                e.error(500, "INTERNAL", String.valueOf(err.getMessage()));
            } finally {
                // 只记 API：静态资源太多，逐条打会把日志刷满
                if (Log.isDebug() && e.path().startsWith("/api/")) {
                    Log.debug("http", e.method() + " " + e.path() + "?" + e.rawQuery()
                            + " → " + (System.currentTimeMillis() - t0) + "ms");
                }
            }
        });
    }

    public int port() {
        return port;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    /** 给测试用：{@code port=0} 由系统分配。 */
    public static HttpService startTest(Router router) throws IOException {
        HttpService s = new HttpService(0, router);
        s.start();
        return s;
    }

    /**
     * 找一个能绑上的端口。
     *
     * <p>显式 {@code --port} 时只用它（用户说了算，绑不上就报错退出）；
     * 默认走 8080，被占用则 8081、8082……最多试 {@link #PORT_TRIES} 次；
     * 全被占用才退回 {@code 0}（系统随机分配）。
     *
     * <p>为什么值得做：端口每次随机的话，用户收藏的地址、脚本里写的
     * {@code curl localhost:8080} 全部失效，每次都要去终端里抄一行新 URL。
     */
    static int bindablePort(int requested) {
        if (requested != DEFAULT_PORT) {
            return requested;
        }
        for (int i = 0; i < PORT_TRIES; i++) {
            int candidate = DEFAULT_PORT + i;
            try (java.net.ServerSocket probe = new java.net.ServerSocket()) {
                probe.setReuseAddress(true);
                probe.bind(new java.net.InetSocketAddress(
                        java.net.InetAddress.getLoopbackAddress(), candidate));
                return candidate;
            } catch (IOException busy) {
                // 换下一个
            }
        }
        return 0;
    }

    /**
     * pid 文件：记下<b>操作系统认的</b>进程号，给 {@code depviz.sh stop} 用。
     *
     * <p>为什么由 Java 写，而不是让脚本记 {@code $!}：Git Bash 里 {@code $!} 是
     * MSYS 自己的编号，和任务管理器、{@code taskkill} 认的 Windows PID 不是一套，
     * 拿它去 {@code kill} 停不掉原生 {@code java.exe}。只有进程自己知道
     * {@code RuntimeMXBean} 里的那个数字。
     *
     * @param file pid 文件，父目录不存在时会先创建
     * @param pid  本进程的系统 pid
     */
    static void writePidFile(java.io.File file, String pid) {
        writeTextFile(file, pid + "\n");
    }

    /**
     * 把实际地址写进文件，给 {@code depviz.sh start} 轮询。
     *
     * <p>为什么不让脚本去解析 stdout：Windows 上 {@code System.out} 用的是控制台
     * 代码页（中文系统是 GBK），脚本按 UTF-8 去 grep 中文标记永远匹配不到 ——
     * 进程明明起来了，{@code start} 还是等满超时然后报「没看到启动完成的标志」。
     * 地址文件里只有 ASCII，和终端编码、日志级别都无关。
     *
     * <p>写失败只记一条警告：脚本那边还有超时兜底，不该因为一个辅助文件起不来
     * 就让整个服务起不来。
     *
     * @param file 地址文件，父目录不存在时会先创建
     * @param url  实际地址，形如 {@code http://127.0.0.1:8080}
     */
    static void writeUrlFile(java.io.File file, String url) {
        writeTextFile(file, url + "\n");
    }

    private static void writeTextFile(java.io.File file, String text) {
        try {
            java.io.File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory()) {
                dir.mkdirs();
            }
            java.nio.file.Files.write(file.toPath(),
                    text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.warn("main", "写状态文件失败: " + file.getAbsolutePath() + " —— " + e.getMessage());
        }
    }

    /**
     * 命令行选项。
     *
     * <p>{@code --log-dir} / {@code --log-file} 是给 {@code depviz.sh} 后台模式用的：
     * 前台运行时日志本来就在终端里，写不写文件都行；后台运行时终端被断开，
     * 不落到文件就什么都留不下。
     *
     * <p>{@code --url-file} 同理，只给脚本用：由 Java 侧报出<b>实际</b>端口，
     * 脚本不猜端口、也不解析终端输出。
     *
     * <p>{@code --pid-file} 记的是本进程的系统 pid。Git Bash 下 {@code $!} 是
     * MSYS 自己的编号，任务管理器和 {@code taskkill} 都不认，只有进程自己知道
     * 那个能让 {@code stop} 真正停掉它的数字。
     */
    private static final class Options {
        int port = DEFAULT_PORT;
        boolean noBrowser = false;
        String logDir;
        String logFile;
        String logLevel;
        String urlFile;
        String pidFile;

        static Options parse(String[] args) {
            Options o = new Options();
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                if ("--port".equals(a) && i + 1 < args.length) {
                    o.port = Integer.parseInt(args[++i]);
                } else if ("--no-browser".equals(a)) {
                    o.noBrowser = true;
                } else if ("--log-dir".equals(a) && i + 1 < args.length) {
                    o.logDir = args[++i];
                } else if ("--log-file".equals(a) && i + 1 < args.length) {
                    o.logFile = args[++i];
                } else if ("--log-level".equals(a) && i + 1 < args.length) {
                    o.logLevel = args[++i];
                } else if ("--url-file".equals(a) && i + 1 < args.length) {
                    o.urlFile = args[++i];
                } else if ("--pid-file".equals(a) && i + 1 < args.length) {
                    o.pidFile = args[++i];
                }
            }
            return o;
        }
    }

    public static void main(String[] args) throws Exception {
        Options opt = Options.parse(args);
        configureLogging(opt);
        // pid 文件要尽早写：脚本靠它区分「还在启动」和「已经死了」。压到
        // service.start() 之后写的话，jar 起不来（-Xmx 写错、缺依赖）时脚本
        // 只能干等到超时，明明进程早就不在了。
        if (opt.pidFile != null) {
            writePidFile(new java.io.File(opt.pidFile), currentPid());
        }
        Log.info("main", "depviz 启动中（pid " + currentPid() + "）");
        Log.info("main", "配置: " + ConfigSummary.of());
        HttpService service = new HttpService(bindablePort(opt.port), Router.defaultRouter());
        installShutdownHook(service);
        service.start();
        // 地址文件最后写：它同时是「启动完成」的信号 —— 脚本轮询到它就报成功，
        // 那时端口已经绑好、可以接请求了。见 writeUrlFile 的注释。
        if (opt.urlFile != null) {
            writeUrlFile(new java.io.File(opt.urlFile), service.baseUrl());
        }
        // 这行 stdout 保留：直接跑 jar 的人要看得见地址。
        System.out.println("depviz 已启动: " + service.baseUrl());
        Log.info("main", "监听 " + service.baseUrl());
        if (!opt.noBrowser) {
            cn.dmego.dev.depviz.desktop.BrowserLauncher.open(service.baseUrl());
        }
    }

    /**
     * 日志级别与落点。
     *
     * <p>级别来自 {@code config.json} 的 {@code logLevel} / {@code fileLogLevel}：
     * 后台进程（{@code depviz.sh start}）没有终端，用户没法在启动时喂参数，
     * 想调级别只能改配置文件再重启。命令行 {@code --log-level} 保留为临时覆盖。
     */
    private static void configureLogging(Options opt) {
        cn.dmego.dev.depviz.config.ConfigStore cfg = cn.dmego.dev.depviz.config.ConfigStore.defaultLocation().load();
        Log.consoleLevel(Log.Level.parse(opt.logLevel == null ? cfg.logLevel() : opt.logLevel));
        Log.fileLevel(Log.Level.parse(cfg.fileLogLevel()));
        if (opt.logFile != null) {
            Log.toFile(new java.io.File(opt.logFile));
            Log.info("main", "日志文件: " + opt.logFile);
            return;
        }
        String dir = opt.logDir;
        if (dir == null) {
            java.io.File d = cn.dmego.dev.depviz.config.ConfigStore.defaultLogDir();
            if (d == null) {
                return;
            }
            dir = d.getAbsolutePath();
        }
        java.io.File f = Log.toFile(new java.io.File(dir, "depviz.log"));
        if (f != null) {
            Log.info("main", "日志文件: " + f);
        }
    }

    /**
     * 退出时把「服务已停止」写进日志，并让 {@code depviz.sh stop} 之后能看出
     * 是正常退出而不是被杀。
     *
     * <p>顺手取消正在跑的扫描。不取消的话：{@code kill} 只打到 JVM，{@code mvn}
     * 是它的子进程，会变成孤儿继续跑几十秒、继续占 {@code -Xmx1024m} 和一个 CPU 核；
     * 下一个实例启动时还会看到「已有扫描在进行」之外的更糟情况 —— 两个 Maven
     * 同时在写各自的 tree.txt，机器直接卡住。
     *
     * @param service 已启动的服务，用它的 job 表找当前扫描
     */
    private static void installShutdownHook(final HttpService service) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Job busy = service.router.projects().jobRegistry().runningJob();
                if (busy != null) {
                    busy.canceller.cancel();
                    Log.info("main", "取消正在进行的扫描（" + busy.id + "）");
                }
            } catch (RuntimeException e) {
                // 退出路径上出什么岔子都不该阻止进程结束
            }
            Log.info("main", "收到退出信号，depviz 停止");
        }, "shutdown"));
    }

    private static String currentPid() {
        try {
            java.lang.management.RuntimeMXBean bean =
                    java.lang.management.ManagementFactory.getRuntimeMXBean();
            return bean.getName().split("@")[0];
        } catch (RuntimeException e) {
            return "?";
        }
    }

    /** 启动时把生效的配置打一遍 —— 排查「为什么扫的是那个 Maven」时全靠它。 */
    private static final class ConfigSummary {
        static String of() {
            try {
                cn.dmego.dev.depviz.config.ConfigStore c = cn.dmego.dev.depviz.config.ConfigStore.defaultLocation().load();
                return "config=" + c.location().getAbsolutePath()
                        + " dataDir=" + c.dataDirFile().getAbsolutePath()
                        + " maven=" + orDefault(c.maven(), "自动探测")
                        + " javaHome=" + orDefault(c.javaHome(), "自动探测")
                        + " offline=" + c.offline()
                        + " xmx=" + orDefault(c.resolvedXmx(), "不注入")
                        + " threads=" + orDefault(c.threads(), "串行")
                        + " log=" + c.logLevel() + "/" + c.fileLogLevel()
                        + " profiles=" + join(c.profileList());
            } catch (RuntimeException e) {
                return "读取配置失败: " + e.getMessage();
            }
        }

        private static String orDefault(String v, String dflt) {
            return v == null || v.isEmpty() ? dflt : v;
        }

        /**
         * 数组直接拼进字符串会打印成 {@code [Ljava.lang.String;@1de0aca6}，日志里没法读。
         * 空数组写成「无」，比一个空串更容易看出「确实没配 profile」。
         */
        private static String join(String[] items) {
            if (items == null || items.length == 0) {
                return "无";
            }
            return String.join(",", items);
        }
    }
}
