package cn.dmego.dev.depviz.json;

/**
 * 手写 JSON 字符串输出。零第三方依赖的代价就是要自己保证转义正确。
 *
 * <p><b>为什么必须转义</b>：本工具的动态字符串特别多 —— 依赖坐标、原始注解、
 * mvn 的 stderr、文件路径、模块名。任何一个带引号或反斜杠都会产出<b>非法 JSON</b>，
 * 前端 {@code JSON.parse} 直接抛异常，整个页面白屏。按 RFC 8259 §7 处理。
 *
 * <p><b>紧凑还是缩进</b>：默认紧凑（一行）。需要给人看、给人改的文件（{@code config.json}、
 * {@code projects.json}）用 {@link #pretty()}。图 JSON 不行 —— 实测 agentscope 的紧凑版
 * 2.0 MB，缩进后体积翻数倍，而它每次扫描写一次、每次重启读一次，纯粹是 I/O 负担。
 */
public final class JsonOut {

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final int MAX_ESCAPE = 0x1f;
    private static final int PRETTY_SPACES = 2;

    private final StringBuilder sb = new StringBuilder(1 << 16);
    private int depth;
    /** 每层缩进的空格数；0 表示紧凑输出。 */
    private int indent;
    /** 当前容器是否还没有元素（决定要不要先写逗号、以及空容器不换行）。 */
    private boolean first = true;
    /** {@link #name(String)} 刚写完，紧接着的值不该再加分隔符和换行。 */
    private boolean afterName;

    public StringBuilder raw() {
        return sb;
    }

    /** 缩进模式（每层 2 个空格）。只给配置文件这类「给人看」的 JSON 用。 */
    public static JsonOut pretty() {
        return new JsonOut().indent(PRETTY_SPACES);
    }

    public JsonOut indent(int spaces) {
        this.indent = spaces < 0 ? 0 : spaces;
        return this;
    }

    public JsonOut beginObject() {
        beforeValue();
        sb.append('{');
        depth++;
        first = true;
        return this;
    }

    public JsonOut endObject() {
        depth--;
        // 空容器写成 {} —— 缩进模式下 { 后面跟一个换行再 } 没有意义
        if (indent > 0 && !first) {
            newline(depth);
        }
        sb.append('}');
        first = false;
        return this;
    }

    public JsonOut beginArray() {
        beforeValue();
        sb.append('[');
        depth++;
        first = true;
        return this;
    }

    public JsonOut endArray() {
        depth--;
        if (indent > 0 && !first) {
            newline(depth);
        }
        sb.append(']');
        first = false;
        return this;
    }

    /** 写字段名（不含值）。 */
    public JsonOut name(String key) {
        beforeValue();
        literal(key);
        sb.append(':');
        if (indent > 0) {
            sb.append(' ');
        }
        afterName = true;
        return this;
    }

    public JsonOut value(String v) {
        beforeValue();
        if (v == null) {
            sb.append("null");
        } else {
            literal(v);
        }
        first = false;
        return this;
    }

    public JsonOut value(int v) {
        beforeValue();
        sb.append(v);
        first = false;
        return this;
    }

    public JsonOut value(long v) {
        beforeValue();
        sb.append(v);
        first = false;
        return this;
    }

    public JsonOut value(boolean v) {
        beforeValue();
        sb.append(v);
        first = false;
        return this;
    }

    public JsonOut value(double v) {
        beforeValue();
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            sb.append('0');
        } else if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            sb.append((long) v);
        } else {
            sb.append(v);
        }
        first = false;
        return this;
    }

    /** 写一个已经序列化好的片段（调用方保证合法）。 */
    public JsonOut rawValue(String preSerialized) {
        beforeValue();
        sb.append(preSerialized);
        first = false;
        return this;
    }

    public JsonOut nullValue() {
        beforeValue();
        sb.append("null");
        first = false;
        return this;
    }

    /** 便捷方法：{@code name(k).value(v)}。 */
    public JsonOut field(String k, String v) {
        return name(k).value(v);
    }

    public JsonOut field(String k, int v) {
        return name(k).value(v);
    }

    public JsonOut field(String k, long v) {
        return name(k).value(v);
    }

    public JsonOut field(String k, boolean v) {
        return name(k).value(v);
    }

    public JsonOut field(String k, double v) {
        return name(k).value(v);
    }

    private void beforeValue() {
        if (depth == 0) {
            return;
        }
        if (afterName) {
            // 值紧跟在 `"key": ` 后面：既不加逗号，也不换行
            afterName = false;
            return;
        }
        if (!first) {
            sb.append(',');
        }
        first = false;
        if (indent > 0) {
            newline(depth);
        }
    }

    /** 换行并按层缩进。只在 {@link #indent} 大于 0 时被调用。 */
    private void newline(int atDepth) {
        sb.append('\n');
        int spaces = atDepth * indent;
        for (int i = 0; i < spaces; i++) {
            sb.append(' ');
        }
    }

    private void literal(String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c <= MAX_ESCAPE) {
                        // 其余控制字符必须写成 \\u00XX，否则 JSON.parse 会失败
                        sb.append("\\u00").append(HEX[(c >> 4) & 0xf]).append(HEX[c & 0xf]);
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    @Override
    public String toString() {
        return sb.toString();
    }

    /** 单值转义，供 {@code JsonWriter} 在拼裸片段时复用。 */
    public static String quote(String s) {
        JsonOut o = new JsonOut();
        o.value(s);
        return o.toString();
    }
}
