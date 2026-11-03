package cn.dmego.dev.depviz.model;

import java.util.List;

/**
 * 图的不变式校验。每次 {@code TreeParser.parse} 之后都应该调一次 ——
 * 一旦不变式破了，下游（前端 {@code engine.js}）会画出错误的图而不是报错。
 */
public final class GraphInvariants {

    /** 一条被破坏的不变式。 */
    public static final class Violation {
        public final String rule;
        public final String detail;

        Violation(String rule, String detail) {
            this.rule = rule;
            this.detail = detail;
        }

        @Override
        public String toString() {
            return rule + ": " + detail;
        }
    }

    private GraphInvariants() {
    }

    /**
     * @return 全部违反项；空列表表示全部通过
     */
    public static List<Violation> check(Graph g, List<Violation> out) {
        checkParentOrder(g, out);
        checkParentConsistency(g, out);
        checkNoCycle(g, out);
        checkOmittedIsLeaf(g, out);
        checkModuleRoots(g, out);
        return out;
    }

    /** 父下标必须小于子下标 —— 前端 {@code Engine.build} 依赖单次正序建 children。 */
    private static void checkParentOrder(Graph g, List<Violation> out) {
        List<Occurrence> all = g.occurrences();
        for (int i = 0; i < all.size(); i++) {
            Occurrence o = all.get(i);
            if (o.parent >= i) {
                out.add(new Violation("parent-order",
                        "occ " + i + " 的 parent=" + o.parent + " 不小于自身下标"));
            }
        }
    }

    /** 每个非根节点的 parent 必须真的在 children 里记着它。 */
    private static void checkParentConsistency(Graph g, List<Violation> out) {
        List<Occurrence> all = g.occurrences();
        for (Occurrence o : all) {
            if (o.parent < 0) {
                continue;
            }
            if (!g.childrenOf(o.parent).contains(o.id)) {
                out.add(new Violation("parent-consistency",
                        "occ " + o.id + " 的 parent=" + o.parent
                                + " 的 children 里没有它"));
            }
        }
    }

    /** 沿 parent 上溯必须能到 -1；步数超过节点总数即为环。 */
    private static void checkNoCycle(Graph g, List<Violation> out) {
        List<Occurrence> all = g.occurrences();
        int guard = all.size() + 1;
        for (Occurrence o : all) {
            int p = o.parent;
            int steps = 0;
            while (p >= 0) {
                if (++steps > guard) {
                    out.add(new Violation("no-cycle", "occ " + o.id + " 上溯超过 " + guard + " 步"));
                    break;
                }
                p = all.get(p).parent;
            }
        }
    }

    /** 实测 0 反例：被省略的节点没有子节点。 */
    private static void checkOmittedIsLeaf(Graph g, List<Violation> out) {
        for (Occurrence o : g.occurrences()) {
            if (!o.effective && !g.childrenOf(o.id).isEmpty()) {
                out.add(new Violation("omitted-is-leaf",
                        "被省略的 occ " + o.id + " 有 " + g.childrenOf(o.id).size() + " 个子节点"));
            }
        }
    }

    /** 模块根必须都是 {@code parent < 0}，且 moduleRoots 与之一一对应。 */
    private static void checkModuleRoots(Graph g, List<Violation> out) {
        int expected = 0;
        for (Occurrence o : g.occurrences()) {
            if (o.parent < 0) {
                expected++;
            }
        }
        if (expected != g.moduleRoots().size()) {
            out.add(new Violation("module-roots",
                    "parent<0 的节点有 " + expected + " 个，moduleRoots 有 " + g.moduleRoots().size() + " 个"));
        }
    }
}
