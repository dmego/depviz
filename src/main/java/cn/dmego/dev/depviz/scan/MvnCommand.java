package cn.dmego.dev.depviz.scan;

import cn.dmego.dev.depviz.env.MavenExecutable;

import java.util.ArrayList;
import java.util.List;

/**
 * 将 {@link ScanRequest} 转换为 Maven 命令行参数。
 *
 * <p>使用全限定 dependency-plugin 坐标以固定插件版本和 verbose 行为；每次扫描使用新输出路径，
 * 因 Maven 以追加模式写入。{@code -o} 和 {@code -T} 按配置添加，并排在 goal 之前。
 */
public final class MvnCommand {

    private MvnCommand() {
    }

    public static List<String> build(ScanRequest r) {
        List<String> cmd = new ArrayList<String>();
        // 解析 Windows Maven 脚本路径，确保 ProcessBuilder 使用可执行文件。
        cmd.add(MavenExecutable.resolve(r.mavenExecutable));
        cmd.add("-B");
        // Maven 构建选项必须放在 goal 之前。
        String parallel = parallelFlag(r.threads);
        if (!parallel.isEmpty()) {
            cmd.add("-T");
            cmd.add(parallel);
        }
        if (r.offline) {
            cmd.add("-o");
        }
        if (r.settingsXml != null && !r.settingsXml.isEmpty()) {
            cmd.add("-s");
            cmd.add(r.settingsXml);
        }
        if (!r.profiles.isEmpty()) {
            for (String p : r.profiles) {
                cmd.add("-P");
                cmd.add(p);
            }
        }
        if (r.localRepo != null && !r.localRepo.isEmpty()) {
            cmd.add("-Dmaven.repo.local=" + r.localRepo);
        }
        cmd.add("org.apache.maven.plugins:maven-dependency-plugin:" + r.pluginVersion + ":tree");
        cmd.add("-Dverbose");
        cmd.add("-DoutputFile=" + r.outputFile.getAbsolutePath());
        cmd.add("-DappendOutput=true");
        return cmd;
    }

    /**
     * 归一化 {@code -T}：空值、{@code 1}、{@code off}/{@code none}/{@code -} 表示串行；其他值原样传给 Maven。
     *
     * <p>{@code -T 1} 仍会启用并行构建器，可能改变依赖树行序，因此按串行处理。
     */
    static String parallelFlag(String threads) {
        if (threads == null) {
            return "";
        }
        String v = threads.trim();
        if (v.isEmpty() || "1".equals(v) || "off".equalsIgnoreCase(v)
                || "none".equalsIgnoreCase(v) || "-".equals(v)) {
            return "";
        }
        return v;
    }

    /** 供 UI「本次扫描详情」展示的命令行（不含可执行路径，从 {@code -B} 开始）。 */
    public static String display(ScanRequest r) {
        List<String> all = build(r);
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < all.size(); i++) {
            String a = all.get(i);
            // 用稳定占位符替代每次扫描变化的临时输出路径。
            if (a.startsWith("-DoutputFile=")) {
                a = "-DoutputFile=<data>/tree.txt";
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(a);
        }
        return sb.toString();
    }
}
