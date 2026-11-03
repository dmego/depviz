package cn.dmego.dev.depviz.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一个 Maven 模块。{@code relPath} 是相对 reactor 根的路径（根自身是 {@code "."}），
 * 前端用它还原模块层级（`ModulePicker` 的三层树）。
 */
public final class Module {

    public final String groupId;
    public final String artifactId;
    /** 本地模型解析后的有效版本，与 groupId/artifactId 一起标识 reactor 模块。 */
    public final String version;
    public final String packaging;
    public final String relPath;
    /** 相对 reactor 根的实际 POM 文件路径，与用于界面层级的 relPath 分开。 */
    public final String pomPath;
    public final int declaredDeps;
    public final int childCount;
    public final List<Declared> declared;

    public Module(String groupId, String artifactId, String version, String packaging,
                  String relPath, String pomPath, int declaredDeps, int childCount,
                  List<Declared> declared) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version == null ? "" : version;
        this.packaging = packaging;
        this.relPath = relPath;
        this.pomPath = pomPath == null ? "" : pomPath;
        this.declaredDeps = declaredDeps;
        this.childCount = childCount;
        this.declared = declared == null
                ? Collections.<Declared>emptyList()
                : Collections.unmodifiableList(new ArrayList<Declared>(declared));
    }

    /** 一条直接声明依赖。{@code optional} 保持原始字符串（{@code ""} 或 {@code "true"}）。 */
    public static final class Declared {
        public final String groupId;
        public final String artifactId;
        public final String version;
        public final String scope;
        public final String optional;

        /**
         * 五个连续的 {@code String} 位置参数最容易传反（编译期完全查不出来），所以用链式构造。
         *
         * <p>{@code optional} 保持**原始字符串**（{@code ""} 或 {@code "true"}），不转 boolean：
         * 前端 `views.js` 用 `d.optional === 'true'` 严格比较，302 条里 238 条空串、64 条 "true"。
         */
        public static final class Builder {
            private String groupId = "";
            private String artifactId = "";
            private String version = "";
            private String scope = "";
            private String optional = "";

            public Builder groupId(String v) { this.groupId = v == null ? "" : v; return this; }

            public Builder artifactId(String v) { this.artifactId = v == null ? "" : v; return this; }

            public Builder version(String v) { this.version = v == null ? "" : v; return this; }

            public Builder scope(String v) { this.scope = v == null ? "" : v; return this; }

            public Builder optional(String v) { this.optional = v == null ? "" : v; return this; }

            public Declared build() {
                return new Declared(groupId, artifactId, version, scope, optional);
            }
        }

        private Declared(String groupId, String artifactId, String version, String scope, String optional) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.scope = scope;
            this.optional = optional;
        }
    }
}
