package cn.dmego.dev.depviz.http;

import cn.dmego.dev.depviz.log.Log;
import cn.dmego.dev.depviz.json.JsonOut;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** 为 {@code GET /api/list-dir} 提供只读目录列表和 Maven 项目提示。 */
final class DirList {

    /** 单次返回的子目录上限。 */
    private static final int MAX_ENTRIES = 500;

    private DirList() {
    }

    /** @param raw 绝对路径；空串表示用户主目录 */
    static void respond(Exchange e, String raw) throws IOException {
        String requested = raw == null ? "" : raw.trim();
        File dir = requested.isEmpty()
                ? new File(System.getProperty("user.home"))
                : new File(normalizeRoot(requested));
        if (!dir.isAbsolute()) {
            throw new IllegalArgumentException("path 必须是绝对路径: " + requested);
        }
        if (!dir.isDirectory()) {
            // 路径不存在时尝试从现存父目录继续浏览。
            File parent = dir.getParentFile();
            if (parent != null && parent.isDirectory()) {
                dir = parent;
            } else {
                e.error(404, "DIR_NOT_FOUND", "目录不存在: " + requested);
                return;
            }
        }
        File canonical = dir.getCanonicalFile();
        File[] kids = canonical.listFiles();
        List<File> dirs = new ArrayList<File>();
        if (kids != null) {
            for (File k : kids) {
                // 跟随有效目录符号链接；断链或不可访问的目录会被跳过。
                if (k.isDirectory() && !k.getName().startsWith(".")) {
                    dirs.add(k);
                }
            }
        }
        dirs.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        boolean truncated = dirs.size() > MAX_ENTRIES;
        if (truncated) {
            dirs = dirs.subList(0, MAX_ENTRIES);
            Log.warn("dir", "目录项过多，只返回前 " + MAX_ENTRIES + " 个: " + canonical);
        }
        File parentDir = canonical.getParentFile();
        e.json(200, body(canonical, parentDir, dirs, truncated));
    }

    /** 规范 Windows 盘符输入；其他平台保持原样。 */
    static String normalizeRoot(String raw) {
        if (!isWindows()) {
            return raw;
        }
        String s = raw.replace('/', '\\');
        if (s.matches("^[A-Za-z]:$")) {
            return s + "\\";
        }
        return s;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static String body(File dir, File parent, List<File> dirs, boolean truncated) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        sb.append("\"path\":").append(JsonOut.quote(dir.getAbsolutePath()));
        sb.append(",\"name\":").append(JsonOut.quote(dir.getName()));
        sb.append(",\"parent\":").append(parent == null ? "null" : JsonOut.quote(parent.getAbsolutePath()));
        sb.append(",\"home\":").append(JsonOut.quote(System.getProperty("user.home")));
        sb.append(",\"roots\":").append(roots());
        // 服务端按平台路径规则生成面包屑，兼容 Windows。
        sb.append(",\"crumbs\":").append(crumbs(dir));
        sb.append(",\"truncated\":").append(truncated);
        // 提供 POM 提示，避免前端逐层查找。
        sb.append(",\"hasPom\":").append(new File(dir, "pom.xml").isFile());
        sb.append(",\"hasModules\":").append(hasModuleElement(new File(dir, "pom.xml")));
        sb.append(",\"dirs\":[");
        for (int i = 0; i < dirs.size(); i++) {
            File d = dirs.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append('{');
            sb.append("\"name\":").append(JsonOut.quote(d.getName()));
            sb.append(",\"path\":").append(JsonOut.quote(d.getAbsolutePath()));
            sb.append(",\"hasPom\":").append(new File(d, "pom.xml").isFile());
            sb.append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    /**
     * 当前可切换的根。Windows 上每个盘符一项（{@code C:\}、{@code D:\}）；
     * macOS / Linux 只有 {@code /} 一项，前端在只有一项时不显示盘符条。
     *
     */
    private static String roots() {
        StringBuilder sb = new StringBuilder(32);
        sb.append('[');
        File[] roots = File.listRoots();
        if (roots != null) {
            for (int i = 0; i < roots.length; i++) {
                File r = roots[i];
                if (i > 0) {
                    sb.append(',');
                }
                sb.append('{');
                sb.append("\"name\":").append(JsonOut.quote(label(r)));
                sb.append(",\"path\":").append(JsonOut.quote(r.getAbsolutePath()));
                sb.append('}');
            }
        }
        sb.append(']');
        return sb.toString();
    }

    /** 返回从文件系统根目录到当前目录的面包屑层级。 */
    private static String crumbs(File dir) {
        List<File> chain = new ArrayList<File>();
        for (File f = dir; f != null; f = f.getParentFile()) {
            chain.add(f);
        }
        StringBuilder sb = new StringBuilder(96);
        sb.append('[');
        for (int i = chain.size() - 1; i >= 0; i--) {
            File f = chain.get(i);
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append('{');
            sb.append("\"name\":").append(JsonOut.quote(label(f)));
            sb.append(",\"path\":").append(JsonOut.quote(f.getAbsolutePath()));
            sb.append('}');
        }
        sb.append(']');
        return sb.toString();
    }

    /** 根路径使用完整路径作为显示名称。 */
    private static String label(File f) {
        String name = f.getName();
        return name.isEmpty() ? f.getAbsolutePath() : name;
    }

    /** 读取 POM 前 8 KiB 检查 {@code <modules>}，仅用于界面提示。 */
    private static boolean hasModuleElement(File pom) {
        if (!pom.isFile()) {
            return false;
        }
        try {
            byte[] head = new byte[8192];
            InputStream in = Files.newInputStream(pom.toPath());
            try {
                int n = in.read(head);
                String s = new String(head, 0, Math.max(n, 0), StandardCharsets.UTF_8);
                return s.contains("<modules>");
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return false;
        }
    }
}
