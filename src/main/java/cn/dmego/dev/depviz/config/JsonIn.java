package cn.dmego.dev.depviz.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 读取器，只为读 {@code ~/.depviz/config.json} 与解析缓存。
 *
 * <p>HTTP 层只需要<b>写</b> JSON（响应），但本地文件层需要<b>读</b> —— 这是零第三方依赖的
 * 唯一缺口，代价是约 150 行。不做流式、不做 schema 绑定，只产出 {@code Map/List/String/
 * Double/Boolean/null} 这五种。
 *
 * <p>配置加载时，文件不存在返回空 Map，字段缺失用默认值补；
 * <b>未知字段必须原样保留</b> —— 否则将来加字段时老配置会被清空。
 */
public final class JsonIn {

    /**
     * 嵌套深度上限。
     *
     * <p>请求体来自 {@code POST /api/config}，是这个本机服务唯一的用户输入面。
     * {@code [[[[…]]]]} 这种一万层数组会一路递归到 {@link StackOverflowError} ——
     * 那是 {@link Error} 不是 {@link RuntimeException}，HTTP 层拦不住，线程直接死。
     * 64 层远超任何合法配置（最深的用例是「未来字段里嵌一个对象」）。
     */
    private static final int MAX_DEPTH = 64;

    private final String src;
    private int pos;
    private int depth;

    private JsonIn(String src) {
        this.src = src;
    }

    /** 解析失败抛 {@link JsonParseException}。 */
    public static Object parse(String text) {
        JsonIn p = new JsonIn(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.pos != p.src.length()) {
            throw new JsonParseException("多余内容 @ " + p.pos);
        }
        return v;
    }

    /** 解析成对象；顶层不是对象时抛异常。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new JsonParseException("顶层不是对象");
        }
        return (Map<String, Object>) v;
    }

    private Object value() {
        if (pos >= src.length()) {
            throw new JsonParseException("意外结束");
        }
        if (depth > MAX_DEPTH) {
            throw new JsonParseException("嵌套超过 " + MAX_DEPTH + " 层 @ " + pos);
        }
        char c = src.charAt(pos);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': return literal("true", Boolean.TRUE);
            case 'f': return literal("false", Boolean.FALSE);
            case 'n': return literal("null", null);
            default: return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        pos++; // {
        depth++;
        try {
            ws();
            if (peek() == '}') {
                pos++;
                return m;
            }
            while (true) {
                ws();
                String k = string();
                ws();
                expect(':');
                ws();
                m.put(k, value());
                ws();
                char c = next();
                if (c == '}') {
                    return m;
                }
                if (c != ',') {
                    throw new JsonParseException("对象里期望 , 或 } @ " + pos);
                }
            }
        } finally {
            depth--;
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<Object>();
        pos++; // [
        depth++;
        try {
            ws();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                ws();
                list.add(value());
                ws();
                char c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new JsonParseException("数组里期望 , 或 ] @ " + pos);
                }
            }
        } finally {
            depth--;
        }
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char e = next();
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    pos += 4;
                    break;
                default: throw new JsonParseException("非法转义 \\" + e + " @ " + pos);
            }
        }
    }

    private Object number() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        while (pos < src.length() && "0123456789.eE+-".indexOf(src.charAt(pos)) >= 0) {
            pos++;
        }
        String s = src.substring(start, pos);
        if (s.isEmpty() || "-".equals(s)) {
            throw new JsonParseException("非法数字 @ " + start);
        }
        return Double.valueOf(s);
    }

    private Object literal(String text, Object v) {
        if (!src.startsWith(text, pos)) {
            throw new JsonParseException("期望 " + text + " @ " + pos);
        }
        pos += text.length();
        return v;
    }

    private void ws() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    private char peek() {
        return pos < src.length() ? src.charAt(pos) : '\0';
    }

    private char next() {
        if (pos >= src.length()) {
            throw new JsonParseException("意外结束");
        }
        return src.charAt(pos++);
    }

    private void expect(char c) {
        if (next() != c) {
            throw new JsonParseException("期望 " + c + " @ " + (pos - 1));
        }
    }
}
