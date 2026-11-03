package cn.dmego.dev.depviz.parse;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将 {@code mvn dependency:tree -Dverbose} 的单行文本解析为 {@link ParsedLine}。
 *
 * <p>解析 Maven 坐标、树缩进和省略注解；无法识别的行交由调用方处理。
 */
public final class TreeLineParser {

    /**
     * 解析每层 3 字符的缩进，以及 ASCII（{@code +- }、{@code \- }）和 Unicode（{@code ├─ }、{@code └─ }）连接符。
     *
     * <p>项目 POM 可将 dependency-plugin 固定为 {@code extended}；此时命令行的
     * {@code -Dtokens=standard} 不能覆盖配置，因此两种格式都必须支持。
     */
    private static final Pattern LINE = Pattern.compile(
            "^((?:\\|  |   |│  )*)(\\+- |\\\\- |└─ |├─ )?(.*)$");

    /** 拒绝坐标中的空白或树形装饰字符，避免将未剥离的缩进解析成假 GA。 */
    private static final Pattern BAD_COORD_CHAR = Pattern.compile("[\\s\\u2500-\\u257F]");

    /** 行尾的整括号注解，如 {@code " (version managed from 1.0)"}。 */
    private static final Pattern TRAILING =
            Pattern.compile("\\s\\(([^()]*)\\)$");

    private static final String A_VERSION_MANAGED_FROM = "version managed from ";
    private static final String A_SCOPE_MANAGED_FROM = "scope managed from ";
    private static final String A_SCOPE_NOT_UPDATED_TO = "scope not updated to ";
    private static final String A_OMITTED_CONFLICT = "omitted for conflict with ";
    private static final String A_OMITTED_DUPLICATE = "omitted for duplicate";

    private static final int PARTS_WITHOUT_CLASSIFIER = 4;
    private static final int PARTS_WITH_SCOPE = 5;
    private static final int PARTS_WITH_CLASSIFIER = 6;
    private static final int INDENT_UNIT = 3;
    private static final String ANNOTATION_SEP = "; ";
    private static final String OMIT_SEP = " - ";

    private TreeLineParser() {
    }

    /**
     * @throws IllegalArgumentException 行内没有任何可解析的坐标（空行由调用方过滤）
     */
    public static ParsedLine parse(String raw) {
        Matcher m = LINE.matcher(raw);
        if (!m.matches()) {
            throw new IllegalArgumentException("unparsable line: " + raw);
        }
        int depth = m.group(1).length() / INDENT_UNIT + (m.group(2) == null ? 0 : 1);
        return parseBody(depth, m.group(3));
    }

    private static ParsedLine parseBody(int depth, String body) {
        boolean omitted = body.length() >= 2 && body.startsWith("(") && body.endsWith(")");
        String coord;
        List<String> annotations = new ArrayList<String>();
        if (omitted) {
            String inner = body.substring(1, body.length() - 1);
            int dash = inner.indexOf(OMIT_SEP);
            coord = dash < 0 ? inner : inner.substring(0, dash);
            addAnnotations(annotations, dash < 0 ? "" : inner.substring(dash + OMIT_SEP.length()));
        } else {
            Matcher tm = TRAILING.matcher(body);
            if (tm.find()) {
                addAnnotations(annotations, tm.group(1));
                coord = body.substring(0, tm.start());
            } else {
                coord = body;
            }
        }
        Coord c = Coord.of(coord);
        ParsedLine.Builder b = new ParsedLine.Builder()
                .depth(depth)
                .coordinate(c.groupId, c.artifactId, c.type, c.classifier, c.version)
                .scope(c.scope)
                .effective(!omitted)
                .annotation(joinAnnotations(annotations));
        applyAnnotations(annotations, b);
        return b.build();
    }

    /** 将已识别的 Maven 注解映射到 builder 的结构化字段。 */
    private static void applyAnnotations(List<String> annotations, ParsedLine.Builder b) {
        for (String ann : annotations) {
            if (ann.startsWith(A_VERSION_MANAGED_FROM)) {
                b.versionManagedFrom(ann.substring(A_VERSION_MANAGED_FROM.length()));
            } else if (ann.startsWith(A_SCOPE_MANAGED_FROM)) {
                b.scopeManagedFrom(ann.substring(A_SCOPE_MANAGED_FROM.length()));
            } else if (ann.startsWith(A_SCOPE_NOT_UPDATED_TO)) {
                b.scopeNotUpdatedTo(ann.substring(A_SCOPE_NOT_UPDATED_TO.length()));
            } else if (ann.startsWith(A_OMITTED_CONFLICT)) {
                b.omitted("conflict", ann.substring(A_OMITTED_CONFLICT.length()));
            } else if (A_OMITTED_DUPLICATE.equals(ann)) {
                b.omitted("duplicate", "");
            }
        }
    }

    /** {@code "a; b"} → {@code ["a", "b"]}；空串也保留一个元素，与参考实现一致。 */
    private static void addAnnotations(List<String> out, String text) {
        for (String s : text.split(ANNOTATION_SEP, -1)) {
            out.add(s);
        }
    }

    private static String joinAnnotations(List<String> annotations) {
        if (annotations.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < annotations.size(); i++) {
            if (i > 0) {
                sb.append(ANNOTATION_SEP);
            }
            sb.append(annotations.get(i));
        }
        return sb.toString();
    }

    /** 4 / 5 / 6 段坐标。6 段形如 {@code g:a:type:classifier:version:scope}。 */
    private static final class Coord {
        final String groupId;
        final String artifactId;
        final String type;
        final String classifier;
        final String version;
        final String scope;

        private Coord(String groupId, String artifactId, String type, String classifier,
                      String version, String scope) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.type = type;
            this.classifier = classifier;
            this.version = version;
            this.scope = scope;
        }

        /** Maven 的全部合法 scope。5 段坐标的最后一段必须是其中之一。 */
        private static final java.util.Set<String> KNOWN_SCOPES = new java.util.HashSet<>(
                java.util.Arrays.asList("compile", "provided", "runtime", "test", "system", "import"));

        /**
         * 用于区分五段坐标中的 {@code version:scope} 与 {@code classifier:version}。
         *
         * <p>首字符为数字，或字段为旧式 {@code RELEASE}/{@code LATEST} 时视为版本；
         * 不按“包含数字”判断，以免将 {@code linux-x86_64} 等 classifier 误判。
         * 仅在末段不是已知 scope 时使用；两段都像版本时拒绝猜测，避免污染版本清单。
         */
        static boolean looksLikeVersion(String s) {
            if (s == null || s.isEmpty()) {
                return false;
            }
            char c = s.charAt(0);
            return (c >= '0' && c <= '9')
                    || "RELEASE".equalsIgnoreCase(s)
                    || "LATEST".equalsIgnoreCase(s);
        }

        static Coord of(String coord) {
            String[] p = coord.split(":", -1);
            for (String part : p) {
                if (BAD_COORD_CHAR.matcher(part).find()) {
                    // 缩进装饰未剥离；继续解析会生成假 GA。
                    throw new IllegalArgumentException("坐标里含空白或装饰字符: " + coord);
                }
            }
            if (p.length == PARTS_WITHOUT_CLASSIFIER) {
                return new Coord(p[0], p[1], p[2], "", p[3], "");
            }
            if (p.length == PARTS_WITH_SCOPE) {
                // 五段坐标是 version:scope 或 classifier:version；先识别已知 scope，再用版本特征区分。
                if (KNOWN_SCOPES.contains(p[4])) {
                    return new Coord(p[0], p[1], p[2], "", p[3], p[4]);
                }
                boolean v3 = looksLikeVersion(p[3]);
                boolean v4 = looksLikeVersion(p[4]);
                if (v3 && !v4) {
                    // Maven 可能输出非标准 scope；保留原值，不因此拒绝依赖。
                    return new Coord(p[0], p[1], p[2], "", p[3], p[4]);
                }
                if (!v3 && v4) {
                    // 保留 classifier:version；若把 classifier 当版本会污染版本数据。
                    return new Coord(p[0], p[1], p[2], p[3], p[4], "");
                }
                // 布局有歧义时抛出异常，不猜测。
                throw new IllegalArgumentException(
                        "5 段坐标无法判定: " + coord
                                + "（第 4 段像版本=" + v3 + "，第 5 段像版本=" + v4
                                + "，第 5 段也不是已知 scope）");
            }
            if (p.length == PARTS_WITH_CLASSIFIER) {
                return new Coord(p[0], p[1], p[2], p[3], p[4], p[5]);
            }
            throw new IllegalArgumentException("bad coordinate (" + p.length + " parts): " + coord);
        }
    }
}
