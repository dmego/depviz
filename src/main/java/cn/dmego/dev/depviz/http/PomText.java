package cn.dmego.dev.depviz.http;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 按需读模块的 POM 原文。
 *
 * <p>为什么不在扫描时把 POM 塞进 graph.json：实测 61 个模块的 POM 共 224468 个字符，
 * 会让 JSON 从 2046466 B 涨到 2278690 B（gzip 从 164907 涨到 181995 B），
 * 而用户打开 POM 弹窗的概率极低。按需读磁盘既省带宽也保证看到的是<b>当前</b>文件
 * —— 用户可能刚在编辑器里改过。
 *
 * <p>模块编号对应 {@link ProjectState.Payload#pomPaths} 中的实际文件身份，
 * 与图的 {@code occ[].m} 同源。{@code relPaths} 只用于显示目录层级，
 * 不能再拼接固定 pom.xml 推测文件（Maven 允许模块使用 custom.xml）。
 */
final class PomText {

    final String relPath;
    final String pomPath;
    final String text;

    PomText(String relPath, String pomPath, String text) {
        this.relPath = relPath;
        this.pomPath = pomPath;
        this.text = text;
    }

    /**
     * @return POM 内容；项目不存在、模块下标越界或文件缺失时返回 {@code null}
     */
    static PomText read(ProjectState p, int moduleId) throws IOException {
        ProjectState.Payload payload = p == null ? null : p.payload;
        if (payload == null || moduleId < 0 || moduleId >= payload.pomPaths.length) {
            return null;
        }
        String path = payload.pomPaths[moduleId];
        if (path.isEmpty()) {
            return null;
        }
        String rel = payload.relPaths[moduleId];
        File pomFile = locate(p.key, path);
        if (pomFile == null || !pomFile.isFile()) {
            return null;
        }
        return new PomText(rel, pomFile.getAbsolutePath(),
                new String(Files.readAllBytes(pomFile.toPath()), StandardCharsets.UTF_8));
    }

    /** 根据图中保存的实际 POM 路径读取，仍限定在项目根内部。 */
    private static File locate(String rootDir, String rel) {
        if (rootDir == null || rootDir.isEmpty()) {
            return null;
        }
        File pom = new File(rootDir, rel);
        return inside(rootDir, pom) ? pom : null;
    }

    private static boolean inside(String rootDir, File file) {
        try {
            String root = new File(rootDir).getCanonicalPath() + File.separator;
            return file.getCanonicalPath().startsWith(root);
        } catch (IOException e) {
            return false;
        }
    }
}
