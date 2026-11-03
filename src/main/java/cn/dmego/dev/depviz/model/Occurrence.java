package cn.dmego.dev.depviz.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 依赖树上的一个节点。对应 graph.json 里 {@code occ} 数组的一项。
 *
 * <p>字段名与图 JSON 的紧凑键一一对应（{@code i/p/ga/v/e/m/t/c/s/o/ob/vm/sm/snu/ann}），
 * 因为前端 {@code engine.js} 直接按这些键读。空字符串表示「没有这个注解」，
 * 序列化时整键省略。
 */
public final class Occurrence {
    public final int id;
    public final int parent;
    public final int gaId;
    public final int versionId;
    public final boolean effective;
    public final int moduleId;
    public final String type;
    public final String classifier;
    public final String scope;
    /** conflict / duplicate，空串表示未省略。 */
    public final String omitReason;
    public final String omittedBy;
    public final String versionManagedFrom;
    public final String scopeManagedFrom;
    public final String scopeNotUpdatedTo;
    /** 原始注解全文（自由文本，不可枚举）。 */
    public final String annotation;

    private Occurrence(Builder b) {
        this.id = b.id;
        this.parent = b.parent;
        this.gaId = b.gaId;
        this.versionId = b.versionId;
        this.effective = b.effective;
        this.moduleId = b.moduleId;
        this.type = b.type;
        this.classifier = b.classifier;
        this.scope = b.scope;
        this.omitReason = b.omitReason;
        this.omittedBy = b.omittedBy;
        this.versionManagedFrom = b.versionManagedFrom;
        this.scopeManagedFrom = b.scopeManagedFrom;
        this.scopeNotUpdatedTo = b.scopeNotUpdatedTo;
        this.annotation = b.annotation;
    }

    public boolean isRoot() {
        return parent < 0;
    }

    /** 供不变式与序列化使用的图 JSON 紧凑键顺序。 */
    public static List<String> compactKeys() {
        List<String> k = new ArrayList<String>();
        k.add("i");
        k.add("p");
        k.add("ga");
        k.add("v");
        k.add("e");
        k.add("m");
        k.add("t");
        k.add("c");
        k.add("s");
        k.add("o");
        k.add("ob");
        k.add("vm");
        k.add("sm");
        k.add("snu");
        k.add("ann");
        return k;
    }

    public static final class Builder {
        private int id = -1;
        private int parent = -1;
        private int gaId;
        private int versionId;
        private boolean effective;
        private int moduleId;
        private String type = "jar";
        private String classifier = "";
        private String scope = "";
        private String omitReason = "";
        private String omittedBy = "";
        private String versionManagedFrom = "";
        private String scopeManagedFrom = "";
        private String scopeNotUpdatedTo = "";
        private String annotation = "";

        /**
         * 树里的位置：occ id、父 occ id（根为 -1）、模块下标。
         *
         * <p>六个位置参数全是 {@code int}/{@code boolean}，顺序写错编译期查不出来，
         * 所以按语义拆成三个链式方法（位置 / 坐标 / 有效性）。
         */
        public Builder slot(int id, int parent, int moduleId) {
            this.id = id;
            this.parent = parent;
            this.moduleId = moduleId;
            return this;
        }

        /** 坐标：GA 下标与版本下标。 */
        public Builder coordinate(int gaId, int versionId) {
            this.gaId = gaId;
            this.versionId = versionId;
            return this;
        }

        /** 是否生效（没被 Maven 剪掉）。 */
        public Builder effective(boolean v) {
            this.effective = v;
            return this;
        }

        public Builder type(String v) { this.type = nz(v, "jar"); return this; }
        public Builder classifier(String v) { this.classifier = nz(v, null); return this; }
        public Builder scope(String v) { this.scope = nz(v, null); return this; }
        public Builder omitReason(String v) { this.omitReason = nz(v, null); return this; }
        public Builder omittedBy(String v) { this.omittedBy = nz(v, null); return this; }
        public Builder versionManagedFrom(String v) { this.versionManagedFrom = nz(v, null); return this; }
        public Builder scopeManagedFrom(String v) { this.scopeManagedFrom = nz(v, null); return this; }
        public Builder scopeNotUpdatedTo(String v) { this.scopeNotUpdatedTo = nz(v, null); return this; }
        public Builder annotation(String v) { this.annotation = nz(v, null); return this; }

        public Occurrence build() {
            return new Occurrence(this);
        }

        private static String nz(String v, String fallback) {
            if (v == null || v.isEmpty()) {
                return fallback == null ? "" : fallback;
            }
            return v;
        }
    }
}
