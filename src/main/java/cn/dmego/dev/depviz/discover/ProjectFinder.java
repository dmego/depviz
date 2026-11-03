package cn.dmego.dev.depviz.discover;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 定位 Maven reactor 根目录并递归解析模块。 */
public final class ProjectFinder {

    /** 向上最多找这么多层，避免在异常目录结构上跑飞。 */
    private static final int MAX_WALK_UP = 24;

    private final PomXml pom;

    public ProjectFinder() {
        this(java.util.Collections.<String>emptyList());
    }

    public ProjectFinder(List<String> profiles) {
        this.pom = new PomXml(profiles);
    }

    /** 查找结果。{@code relocated} 表示用户选的不是根。 */
    public static final class Found {
        public final File selectedDir;
        public final File rootDir;
        public final File rootPom;
        public final boolean relocated;
        public final List<File> modulePoms;
        public final List<String> relPaths;

        Found(File selectedDir, File rootPom, boolean relocated,
              List<File> modulePoms, List<String> relPaths) {
            this.selectedDir = selectedDir;
            this.rootDir = rootPom.getParentFile();
            this.rootPom = rootPom;
            this.relocated = relocated;
            this.modulePoms = modulePoms;
            this.relPaths = relPaths;
        }

        public int moduleCount() {
            return modulePoms.size();
        }
    }

    /**
     * 从 {@code dir} 向上找根 pom 并展开模块树。
     *
     * @throws DiscoverException 找不到任何 pom，或模块树为空
     */
    public Found find(File dir) throws DiscoverException {
        if (dir == null || !dir.isDirectory()) {
            throw new DiscoverException("目录不存在: " + dir);
        }
        File selected = dir.getAbsoluteFile();
        File rootPom = locateRootPom(selected);
        if (rootPom == null) {
            throw new DiscoverException("从 " + selected + " 向上没有找到 pom.xml");
        }
        File rootDir = rootPom.getParentFile();
        Walk w = new Walk(rootDir);
        collect(rootPom, w, 0, true);
        if (w.poms.isEmpty()) {
            throw new DiscoverException("模块树为空: " + rootPom);
        }
        return new Found(selected, rootPom, !rootDir.equals(selected), w.poms, w.rel);
    }

    /** 最近的 aggregator 只有在其模块声明包含所选 POM 时才能替代最近的 POM。 */
    private File locateRootPom(File from) {
        File cur = from;
        File best = null;
        for (int i = 0; i < MAX_WALK_UP && cur != null; i++) {
            File candidate = new File(cur, "pom.xml");
            if (candidate.isFile()) {
                if (best == null) {
                    best = candidate;
                }
                if (pom.looksLikeAggregator(candidate) && covers(candidate, best, from)) {
                    return candidate;
                }
            }
            cur = cur.getParentFile();
        }
        return best;
    }

    private boolean covers(File aggregatorPom, File selectedPom, File selectedDir) {
        return coversModule(aggregatorPom, selectedPom, selectedDir, new HashSet<String>(), 0);
    }

    private boolean coversModule(File pomFile, File selectedPom, File selectedDir,
                                 Set<String> seen, int depth) {
        if (depth > MAX_WALK_UP || !seen.add(canonical(pomFile))) {
            return false;
        }
        if (sameFile(pomFile, selectedPom)) {
            return true;
        }
        for (String mod : pom.readModules(pomFile)) {
            File childPom = childPomOf(pomFile, mod);
            if (!childPom.isFile()) {
                continue;
            }
            // 自定义 POM 的目录下没有 pom.xml；目录恰好一致时仍可识别实际模块。
            if ((sameFile(childPom.getParentFile(), selectedDir)
                    && !new File(selectedDir, "pom.xml").isFile())
                    || coversModule(childPom, selectedPom, selectedDir, seen, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameFile(File a, File b) {
        return canonical(a).equals(canonical(b));
    }

    /**
     * {@code <module>} 指向的 pom 文件。
     *
     * <p>一般写的是<b>目录</b>（该目录下的 {@code pom.xml}），但 Maven 也允许直接写
     * 一个 {@code .xml} 路径（{@code <module>child/custom.xml</module>}）。
     */
    private static File childPomOf(File parentPom, String mod) {
        File target = new File(parentPom.getParentFile(), mod);
        return target.isFile() ? target : new File(target, "pom.xml");
    }

    /** 递归遍历模块时共享的状态。 */
    private static final class Walk {
        private final File rootDir;
        private final List<File> poms = new ArrayList<File>();
        private final List<String> rel = new ArrayList<String>();
        private final Set<String> seen = new HashSet<String>();

        Walk(File rootDir) {
            this.rootDir = rootDir;
        }
    }

    /**
     * 按规范 POM 路径去重，避免重复模块和递归环。
     * 根 POM 解析失败时终止；子 POM 错误留给 Maven 报告。
     *
     * @param strict 是否在 POM 解析失败时终止
     */
    private void collect(File pomFile, Walk w, int depth, boolean strict) throws DiscoverException {
        if (depth > MAX_WALK_UP) {
            return;
        }
        String canon = canonical(pomFile);
        if (!w.seen.add(canon)) {
            return;
        }
        w.poms.add(pomFile);
        w.rel.add(relativize(w.rootDir, pomFile.getParentFile()));
        List<String> mods = strict ? pom.readModulesStrict(pomFile) : pom.readModules(pomFile);
        for (String mod : mods) {
            // <module> 一般写目录，但 Maven 也接受直接指向某个 .xml
            File childPom = childPomOf(pomFile, mod);
            if (childPom.isFile()) {
                collect(childPom, w, depth + 1, false);
            }
        }
    }

    private static String canonical(File f) {
        try {
            return f.getCanonicalPath();
        } catch (IOException e) {
            return f.getAbsolutePath();
        }
    }

    /** 返回以 {@code /} 分隔的规范模块路径；根目录记为 {@code .}。 */
    static String relativize(File root, File dir) {
        String r = canonical(root);
        String d = canonical(dir);
        if (r.equals(d)) {
            return ".";
        }
        if (d.startsWith(r + File.separator)) {
            return d.substring(r.length() + 1).replace(File.separatorChar, '/');
        }
        // 根目录外的模块使用目录名，避免混入错误层级。
        return dir.getName();
    }
}
