package cn.dmego.dev.depviz.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个 Maven 坐标（groupId:artifactId）及其出现过的全部版本。
 * 对应 graph.json 里 {@code gas} 数组的一项。
 */
public final class Artifact {
    public final String groupId;
    public final String artifactId;
    /** 版本按首次出现顺序追加，下标就是 occ 里 {@code v} 的值。 */
    public final List<String> versions = new ArrayList<String>();

    public Artifact(String groupId, String artifactId) {
        this.groupId = groupId;
        this.artifactId = artifactId;
    }

    public String key() {
        return groupId + ':' + artifactId;
    }

    public String artifactId() {
        return artifactId;
    }

    /** 返回版本下标；不存在则追加。 */
    public int versionId(String version) {
        int i = versions.indexOf(version);
        if (i >= 0) {
            return i;
        }
        versions.add(version);
        return versions.size() - 1;
    }
}
