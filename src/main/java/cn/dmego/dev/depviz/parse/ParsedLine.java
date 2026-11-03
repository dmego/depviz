package cn.dmego.dev.depviz.parse;

import cn.dmego.dev.depviz.model.Occurrence;

import java.util.Arrays;
import java.util.List;

/**
 * 依赖树里的一行解析结果，介于「原始文本」与 {@link Occurrence} 之间。
 *
 * <p>解析分两步：{@link TreeLineParser} 把一行文本变成 {@code ParsedLine}（此时
 * gaId / versionId / moduleId 都还没有，因为它们要由遍历顺序决定），
 * {@link TreeParser} 再做下标分配与父子连接。
 */
public final class ParsedLine {
    /** 行首缩进 + 连接符算出来的深度，模块根为 0。 */
    public final int depth;
    public final String groupId;
    public final String artifactId;
    public final String type;
    public final String classifier;
    public final String version;
    public final String scope;
    public final boolean effective;
    public final String omitReason;
    public final String omittedBy;
    public final String versionManagedFrom;
    public final String scopeManagedFrom;
    public final String scopeNotUpdatedTo;
    public final String annotation;

    private ParsedLine(Builder b) {
        this.depth = b.depth;
        this.groupId = b.groupId;
        this.artifactId = b.artifactId;
        this.type = b.type;
        this.classifier = b.classifier;
        this.version = b.version;
        this.scope = b.scope;
        this.effective = b.effective;
        this.omitReason = b.omitReason;
        this.omittedBy = b.omittedBy;
        this.versionManagedFrom = b.versionManagedFrom;
        this.scopeManagedFrom = b.scopeManagedFrom;
        this.scopeNotUpdatedTo = b.scopeNotUpdatedTo;
        this.annotation = b.annotation;
    }

    /**
     * 造一条解析结果。
     *
     * <p><b>为什么是 builder 而不是 14 个位置参数：</b>调用点原先传的是
     * {@code flags[0], flags[1], flags[2], flags[3], flags[4]} —— 一个按位置下标取的
     * 数组，把 omitReason 和 omittedBy 写反了编译器也不会吭声，只会让界面上
     * 「重复引入」和「被取代」互换。现在每个字段自带名字。
     */
    public static final class Builder {
        private int depth;
        private String groupId;
        private String artifactId;
        private String type;
        private String classifier;
        private String version;
        private String scope;
        private boolean effective;
        private String omitReason;
        private String omittedBy;
        private String versionManagedFrom;
        private String scopeManagedFrom;
        private String scopeNotUpdatedTo;
        private String annotation;

        public Builder depth(int v) {
            this.depth = v;
            return this;
        }

        public Builder coordinate(String g, String a, String t, String c, String v) {
            this.groupId = g;
            this.artifactId = a;
            this.type = t;
            this.classifier = c;
            this.version = v;
            return this;
        }

        public Builder scope(String v) {
            this.scope = v;
            return this;
        }

        public Builder effective(boolean v) {
            this.effective = v;
            return this;
        }

        /** omitReason 与 omittedBy 必须成对给 —— 它们一起描述同一条被剪记录。 */
        public Builder omitted(String reason, String by) {
            this.omitReason = reason;
            this.omittedBy = by;
            return this;
        }

        /**
         * 两个「managed from」是**互相独立**的注解：一条记录可能只有版本被改写、
         * 也可能只有 scope 被改写。所以分成两个方法，避免其中一个覆盖另一个。
         */
        public Builder versionManagedFrom(String v) {
            this.versionManagedFrom = v;
            return this;
        }

        public Builder scopeManagedFrom(String v) {
            this.scopeManagedFrom = v;
            return this;
        }

        public Builder scopeNotUpdatedTo(String v) {
            this.scopeNotUpdatedTo = v;
            return this;
        }

        public Builder annotation(String v) {
            this.annotation = v;
            return this;
        }

        public ParsedLine build() {
            return new ParsedLine(this);
        }
    }

    /**
     * 把解析结果落到一个树位置上。
     *
     * <p>前三个参数（id / parent / moduleId）描述的是**树里的位置**，与
     * {@code ParsedLine} 自己携带的坐标信息（gaId / versionId / …）是两回事，
     * 所以分成两个对象传，而不是五个位置参数。
     */
    public Occurrence.Builder toOccurrence(Slot slot, int gaId, int versionId) {
        return new Occurrence.Builder()
                .slot(slot.id, slot.parent, slot.moduleId)
                .coordinate(gaId, versionId)
                .effective(effective)
                .type(type)
                .classifier(classifier)
                .scope(scope)
                .omitReason(omitReason)
                .omittedBy(omittedBy)
                .versionManagedFrom(versionManagedFrom)
                .scopeManagedFrom(scopeManagedFrom)
                .scopeNotUpdatedTo(scopeNotUpdatedTo)
                .annotation(annotation);
    }

    /**
     * 折叠键：同父 + 同 GA + 同 version + 同 type + 同 classifier + 同 scope + 同 effective。
     *
     * <p><b>type 与 classifier 不能省。</b>实测 agentscope-java 若漏掉这两维，
     * 会把 13 组、52 条 occurrence 错误合并（15355 → 15303）；典型是
     * {@code netty-codec-native-quic} 的 5 个 classifier 挂在同一个父下。
     */
    public List<Object> foldKey() {
        return Arrays.<Object>asList(groupId, artifactId, version, type, classifier, scope, effective);
    }

    /** 一条解析结果在树里的落点：occ id、父 occ id（根为 -1）、模块下标。 */
    public static final class Slot {
        public final int id;
        public final int parent;
        public final int moduleId;

        public Slot(int id, int parent, int moduleId) {
            this.id = id;
            this.parent = parent;
            this.moduleId = moduleId;
        }
    }
}
