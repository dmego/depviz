package cn.dmego.dev.depviz.discover;

import cn.dmego.dev.depviz.model.Module;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 一次模块发现的完整结果，对应 {@code GET /api/discover} 的响应体。 */
public final class ProjectInfo {

    public final String selectedDir;
    public final String rootDir;
    public final String rootPom;
    public final boolean relocated;
    public final long discoverMillis;
    public final List<Module> modules;

    public ProjectInfo(File selectedDir, File rootDir, File rootPom, boolean relocated,
                       long discoverMillis, List<Module> modules) {
        this.selectedDir = selectedDir.getAbsolutePath();
        this.rootDir = rootDir.getAbsolutePath();
        this.rootPom = rootPom.getAbsolutePath();
        this.relocated = relocated;
        this.discoverMillis = discoverMillis;
        this.modules = Collections.unmodifiableList(new ArrayList<Module>(modules));
    }

    public int moduleCount() {
        return modules.size();
    }

    /** 所有模块直接声明依赖数之和。 */
    public int declaredDependencyCount() {
        int n = 0;
        for (Module m : modules) {
            n += m.declaredDeps;
        }
        return n;
    }
}
