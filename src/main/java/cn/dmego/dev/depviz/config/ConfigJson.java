package cn.dmego.dev.depviz.config;

import cn.dmego.dev.depviz.json.JsonOut;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 配置 JSON 的「未知字段」两个方向的搬运。
 *
 * <p>存在的理由：{@code config.json} 允许出现本版本不认识的字段（降级、手工编辑、
 * 未来字段）。读的时候要收进 {@code extra}，写的时候要原样吐回去 —— 否则升级一次
 * 就把用户自己加的字段清空了，且没有任何报错。
 *
 * <p>从 {@link ConfigStore} 拆出来是为了守住「文件 ≤300 行」。
 */
final class ConfigJson {

    /** 本版本认识的 11 个键。其余的进 {@code extra}，原样保留。 */
    static final Set<String> KNOWN = new HashSet<String>(Arrays.asList(
            "maven", "javaHome", "settingsXml", "localRepo",
            "offline", "profiles", "dataDir",
            "logLevel", "fileLogLevel", "xmx", "threads"));

    private ConfigJson() {
    }

    /** 把 load 时读到的任意 JSON 值原样写回，保证未知字段不丢。 */
    static void writeAny(JsonOut o, Object v) {
        if (v == null) {
            o.nullValue();
        } else if (v instanceof String) {
            o.value((String) v);
        } else if (v instanceof Boolean) {
            o.value(((Boolean) v).booleanValue());
        } else if (v instanceof Double) {
            o.value(((Double) v).doubleValue());
        } else if (v instanceof java.util.List) {
            o.beginArray();
            for (Object x : (java.util.List<?>) v) {
                writeAny(o, x);
            }
            o.endArray();
        } else if (v instanceof Map) {
            o.beginObject();
            for (Map.Entry<?, ?> x : ((Map<?, ?>) v).entrySet()) {
                o.name(String.valueOf(x.getKey()));
                writeAny(o, x.getValue());
            }
            o.endObject();
        } else {
            o.value(String.valueOf(v));
        }
    }

    /** 把不认识的键搬进 {@code extra}。认识的键由各自的 setter 处理，这里不碰。 */
    static void retainUnknown(Map<String, Object> loaded, Map<String, Object> extra) {
        for (Map.Entry<String, Object> e : loaded.entrySet()) {
            if (!KNOWN.contains(e.getKey())) {
                extra.put(e.getKey(), e.getValue());
            }
        }
    }
}
