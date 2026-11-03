package cn.dmego.dev.depviz.parse;

import cn.dmego.dev.depviz.model.Artifact;
import cn.dmego.dev.depviz.model.Graph;
import cn.dmego.dev.depviz.model.Occurrence;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析 {@code mvn dependency:tree -Dverbose} 输出为 {@link Graph}。
 *
 * <p>按 Maven 输出顺序以前序构建，保证父 occurrence 的下标小于子项；前端据此构建
 * {@code children}。
 *
 * <p>GA、版本和模块下标均按首次出现顺序分配。前端依赖这些索引，计数相同也无法发现顺序错位。
 *
 * <p>每行保留为一个 occurrence，不合并重复依赖，以维持行数与 occurrence 数一致。
 */
public final class TreeParser {

    private TreeParser() {
    }

    public static Graph parseFile(Path treeFile) throws IOException {
        InputStream in = Files.newInputStream(treeFile);
        try {
            return parse(in, StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    public static Graph parse(InputStream in, Charset charset) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, charset));
        try {
            return parse(reader);
        } finally {
            reader.close();
        }
    }

    /**
     * 解析全量文本。
     *
     * @throws ParseException 出现无法解析的行时抛出，并带上行号与原文
     */
    public static Graph parse(BufferedReader reader) throws IOException {
        Collector c = new Collector();
        String raw;
        while ((raw = reader.readLine()) != null) {
            c.accept(raw);
        }
        return c.build();
    }

    /** 解析过程中的可变累加器。 */
    private static final class Collector {
        private final List<Artifact> artifacts = new ArrayList<Artifact>();
        private final Map<String, Integer> gaIndex = new HashMap<String, Integer>();
        private final List<Occurrence> occurrences = new ArrayList<Occurrence>();
        private final List<Integer> moduleRoots = new ArrayList<Integer>();
        /** 每一层的 occurrence id；栈顶是当前行的父。 */
        private final List<Integer> stack = new ArrayList<Integer>();
        /** parent occ id → 子 occ id 列表，按声明顺序。 */
        private final Map<Integer, List<Integer>> children = new HashMap<Integer, List<Integer>>();
        private int moduleId = -1;
        private int lineNo = 0;

        void accept(String raw) {
            lineNo++;
            if (raw.trim().isEmpty()) {
                return;
            }
            ParsedLine line;
            try {
                line = TreeLineParser.parse(raw);
            } catch (IllegalArgumentException e) {
                throw new ParseException(lineNo, raw, e);
            }
            int parent = resolveParent(line.depth);
            int gaId = gaIdOf(line);
            int versionId = artifacts.get(gaId).versionId(line.version);
            Occurrence occ = line
                    .toOccurrence(new ParsedLine.Slot(occurrences.size(), parent, moduleId), gaId, versionId)
                    .build();
            occurrences.add(occ);
            if (parent < 0) {
                moduleRoots.add(occ.id);
            } else {
                childList(parent).add(occ.id);
            }
            stack.add(occ.id);
        }

        /**
         * depth 0 开始新模块；其它深度通过弹栈查找父项。
         * 栈空时按根处理：Maven 的 -T 并行输出可能缺少父行，解析继续。
         */
        private int resolveParent(int depth) {
            if (depth == 0) {
                moduleId++;
                stack.clear();
                return -1;
            }
            while (stack.size() > depth) {
                stack.remove(stack.size() - 1);
            }
            return stack.isEmpty() ? -1 : stack.get(stack.size() - 1);
        }

        private int gaIdOf(ParsedLine line) {
            String key = line.groupId + ':' + line.artifactId;
            Integer id = gaIndex.get(key);
            if (id != null) {
                return id;
            }
            int newId = artifacts.size();
            gaIndex.put(key, newId);
            artifacts.add(new Artifact(line.groupId, line.artifactId));
            return newId;
        }

        private List<Integer> childList(int parent) {
            List<Integer> list = children.get(parent);
            if (list == null) {
                list = new ArrayList<Integer>();
                children.put(parent, list);
            }
            return list;
        }

        Graph build() {
            return new Graph(artifacts, occurrences, moduleRoots, children);
        }
    }
}
