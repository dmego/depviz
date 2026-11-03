package cn.dmego.dev.depviz.http;

import cn.dmego.dev.depviz.config.ConfigStore;
import cn.dmego.dev.depviz.config.JsonIn;

import java.io.IOException;
import java.util.Map;

/**
 * {@code GET/POST /api/config} 的读写逻辑。
 *
 * <p>从 {@link Router} 里拆出来是为了守住「文件 ≤ 300 行」这条硬约束 ——
 * 不是因为它复杂。合并语义的来龙去脉见 {@link #apply}。
 */
final class ConfigRoutes {

    private final ConfigStore config;

    ConfigRoutes(ConfigStore config) {
        this.config = config;
    }

    void get(Exchange e) throws IOException {
        e.json(200, config.toJson());
    }

    void save(Exchange e) throws IOException {
        String body = e.body();
        if (!body.isEmpty()) {
            try {
                Map<String, Object> m = JsonIn.parseObject(body);
                apply(m);
            } catch (RuntimeException ex) {
                e.error(400, "BAD_REQUEST", "配置 JSON 不合法: " + ex.getMessage());
                return;
            }
        }
        config.save();
        e.json(200, config.toJson());
    }

    /**
     * 把请求体<b>合并</b>进当前配置：只覆盖请求里真的出现的字段。
     *
     * <p>早先的写法是 {@code config.maven(str(m, "maven"))}，{@code str} 对缺失字段返回
     * 空串 —— 于是 {@code POST {"offline":true}} 会把用户的 Maven 路径、profiles
     * 全部清空（实测 {@code /x/mvn} → {@code ""}）。部分更新必须按「字段存在才改」处理，
     * 这也是 02 §6.5.4 写的「缺字段保留原值」。
     */
    private void apply(Map<String, Object> m) {
        String v;
        if ((v = str(m, "maven")) != null) {
            config.maven(v);
        }
        if ((v = str(m, "javaHome")) != null) {
            config.javaHome(v);
        }
        if ((v = str(m, "settingsXml")) != null) {
            config.settingsXml(v);
        }
        if ((v = str(m, "localRepo")) != null) {
            config.localRepo(v);
        }
        if ((v = str(m, "profiles")) != null) {
            config.profiles(v);
        }
        if ((v = str(m, "dataDir")) != null) {
            config.dataDir(v);
        }
        if ((v = str(m, "logLevel")) != null) {
            config.logLevel(v);
        }
        if ((v = str(m, "fileLogLevel")) != null) {
            config.fileLogLevel(v);
        }
        if ((v = str(m, "xmx")) != null) {
            config.xmx(v);
        }
        if ((v = str(m, "threads")) != null) {
            config.threads(v);
        }
        Object off = m.get("offline");
        if (off instanceof Boolean) {
            config.offline(((Boolean) off).booleanValue());
        }
    }

    /**
     * 取一个字符串字段；缺失或类型不对都返回 {@code null}。
     *
     * <p>不能返回空串：调用方按「{@code null} = 不修改」处理，返回空串等于
     * 把用户配的值抹成空。实测 {@code POST {"maven":123}} 曾把 Maven 路径清空，
     * 界面上看起来只是「设置没了」，没有任何报错。
     */
    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof String ? (String) v : null;
    }
}
