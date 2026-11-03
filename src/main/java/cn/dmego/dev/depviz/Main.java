package cn.dmego.dev.depviz;

import cn.dmego.dev.depviz.cli.GraphCommand;

import java.io.File;

/**
 * 入口。
 *
 * <p>无参数启动 = 起本地 Web 服务并开浏览器（正常用法）。
 * 带参数则是命令行旁路，给开发与「解析层是否还正确」的核对用：
 * <pre>
 *   java -jar depviz.jar graph &lt;项目目录&gt; [--tree &lt;tree.txt&gt;] -o &lt;graph.json&gt;
 * </pre>
 */
public final class Main {

    private static final String[] USAGE = {
        "用法:",
        "  java -jar depviz.jar                     启动 Web 服务并打开浏览器",
        "  java -jar depviz.jar serve [--port N] [--no-browser]",
        "                                           指定端口启动",
        "  java -jar depviz.jar scan <dir> [-o out.json] [--offline|--online]",
        "                                           跑完整流程（Maven + 解析），产出 graph.json",
        "  java -jar depviz.jar graph <dir> [-o out.json] [--tree tree.txt] [--stats]",
        "                                           只跑解析层；--stats 只打印统计不写文件",
    };

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            Server.main(new String[0]);
            return;
        }
        String cmd = args[0];
        if ("help".equals(cmd) || "-h".equals(cmd) || "--help".equals(cmd)) {
            printUsage();
            return;
        }
        if ("serve".equals(cmd)) {
            Server.main(slice(args, 1));
            return;
        }
        if ("graph".equals(cmd)) {
            System.exit(graph(slice(args, 1)));
            return;
        }
        if ("scan".equals(cmd)) {
            System.exit(scan(slice(args, 1)));
            return;
        }
        printUsage();
        System.exit(2);
    }

    private static int graph(String[] args) {
        File dir = null;
        File tree = null;
        File out = new File("graph.json");
        boolean stats = false;
        for (int i = 0; i < args.length; i++) {
            if ("-o".equals(args[i]) && i + 1 < args.length) {
                out = new File(args[++i]);
            } else if ("--tree".equals(args[i]) && i + 1 < args.length) {
                tree = new File(args[++i]);
            } else if ("--stats".equals(args[i])) {
                stats = true;
            } else if (!args[i].startsWith("-")) {
                dir = new File(args[i]);
            }
        }
        if (dir == null) {
            System.err.println("缺少项目目录");
            printUsage();
            return 2;
        }
        // `graph <graph.json> --stats`：直接对已有产物出统计，不重跑 Maven。
        // 这比 `graph <dir> --tree <fixture>` 更接近用户手上真有的东西 —— 排查时
        // 通常只有上次扫出来的 json，没有原始 tree.txt。
        if (stats && dir.isFile() && dir.getName().endsWith(".json")) {
            return GraphCommand.statsOfJson(dir);
        }
        try {
            return GraphCommand.run(stats
                    ? GraphCommand.Options.stats(dir, tree)
                    : GraphCommand.Options.of(dir, tree, out));
        } catch (cn.dmego.dev.depviz.parse.ParseException e) {
            // 解析失败是最可能的用户可见错误（tree.txt 被截断、格式变了），
            // 打完整栈没用 —— 用户要的是「第几行、那一行长什么样」。
            System.err.println("解析失败: " + e.getMessage());
            return 1;
        } catch (StackOverflowError e) {
            // 深到几千层的树会让正则里的 (?:...)* 递归爆栈。实测默认栈能到 4000 层，
            // 而真实 Maven 树最深 10 层 —— 真撞上时用户需要知道是「树太深」，
            // 而不是一屏 java.util.regex.Pattern$GroupTail。
            System.err.println("解析失败: 依赖树太深（超过几千层），或者是 tree.txt 格式异常");
            return 1;
        }
    }

    private static int scan(String[] args) {
        File dir = null;
        File out = new File("graph.json");
        Boolean offline = null;
        for (int i = 0; i < args.length; i++) {
            if ("-o".equals(args[i]) && i + 1 < args.length) {
                out = new File(args[++i]);
            } else if ("--offline".equals(args[i])) {
                offline = Boolean.TRUE;
            } else if ("--online".equals(args[i])) {
                offline = Boolean.FALSE;
            } else if (!args[i].startsWith("-")) {
                dir = new File(args[i]);
            }
        }
        if (dir == null) {
            System.err.println("缺少项目目录");
            printUsage();
            return 2;
        }
        try {
            return GraphCommand.scan(dir, out, offline);
        } catch (cn.dmego.dev.depviz.parse.ParseException e) {
            System.err.println("解析失败: " + e.getMessage());
            return 1;
        }
    }

    private static String[] slice(String[] a, int from) {
        String[] out = new String[a.length - from];
        System.arraycopy(a, from, out, 0, out.length);
        return out;
    }

    private static void printUsage() {
        for (String s : USAGE) {
            System.out.println(s);
        }
    }
}
