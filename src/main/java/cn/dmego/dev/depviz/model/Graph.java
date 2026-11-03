package cn.dmego.dev.depviz.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析产出的依赖树。不可变，构建完成后再交给 HTTP 层 / 序列化层。
 *
 * <p>四份核心数据：
 * <ul>
 *   <li>{@link #artifacts} —— 去重后的 GA 列表，下标就是 {@code occ.gaId}</li>
 *   <li>{@link #occurrences} —— 全部树节点，下标就是 occurrence id</li>
 *   <li>{@link #moduleRoots} —— 61 个模块根的 occurrence id，升序</li>
 *   <li>{@link #children} —— 父 occ id → 子 occ id 列表（按声明顺序）</li>
 * </ul>
 *
 * <p>不变式（{@code GraphInvariants} 会逐条校验）：
 * 严格树、每非根恰一个父、无环、父下标小于子下标、被省略的节点是叶子。
 */
public final class Graph {

    private final List<Artifact> artifacts;
    private final List<Occurrence> occurrences;
    private final List<Integer> moduleRoots;
    private final Map<Integer, List<Integer>> children;

    public Graph(List<Artifact> artifacts, List<Occurrence> occurrences,
                 List<Integer> moduleRoots, Map<Integer, List<Integer>> children) {
        this.artifacts = Collections.unmodifiableList(new ArrayList<Artifact>(artifacts));
        this.occurrences = Collections.unmodifiableList(new ArrayList<Occurrence>(occurrences));
        this.moduleRoots = Collections.unmodifiableList(new ArrayList<Integer>(moduleRoots));
        // children 也要包成不可变：图在扫描完成后被缓存、被所有并发请求共享，
        // 任何一个调用方顺手 add 一下，全进程看到的就是另一张图了。
        // 里层每个 List 同样包一层，否则 childrenOf() 返回的内部列表仍可被改。
        Map<Integer, List<Integer>> copy = new HashMap<Integer, List<Integer>>();
        for (Map.Entry<Integer, List<Integer>> e : children.entrySet()) {
            copy.put(e.getKey(), Collections.unmodifiableList(new ArrayList<Integer>(e.getValue())));
        }
        this.children = Collections.unmodifiableMap(copy);
    }

    public List<Artifact> artifacts() {
        return artifacts;
    }

    public List<Occurrence> occurrences() {
        return occurrences;
    }

    public List<Integer> moduleRoots() {
        return moduleRoots;
    }

    public Occurrence occ(int id) {
        return occurrences.get(id);
    }

    public Artifact artifactOf(Occurrence o) {
        return artifacts.get(o.gaId);
    }

    public List<Integer> childrenOf(int occId) {
        List<Integer> list = children.get(occId);
        return list == null ? Collections.<Integer>emptyList() : list;
    }

    public int size() {
        return occurrences.size();
    }

    public int moduleCount() {
        return moduleRoots.size();
    }

    /** 根取 0 的深度：模块根 0，往下一层加 1。用于分段与 {@code maxDepth} 断言。 */
    public int depthOf(int occId) {
        int d = 0;
        int p = occurrences.get(occId).parent;
        while (p >= 0) {
            d++;
            p = occurrences.get(p).parent;
        }
        return d;
    }

    public int maxDepth() {
        int max = 0;
        for (Occurrence o : occurrences) {
            int d = depthOf(o.id);
            if (d > max) {
                max = d;
            }
        }
        return max;
    }
}
