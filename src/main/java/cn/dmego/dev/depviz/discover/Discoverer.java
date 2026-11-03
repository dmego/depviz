package cn.dmego.dev.depviz.discover;

import cn.dmego.dev.depviz.model.Module;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把 {@link ProjectFinder} 找到的 pom 列表翻译成 {@link ProjectInfo}。
 *
 * <p>一次发现实测 3 ms（纯 XML，不跑 Maven），所以每次打开项目都重新算即可，不需要缓存。
 */
public final class Discoverer {

    /** 发现 + 读取全部模块元数据。 */
    public ProjectInfo discover(File dir) throws DiscoverException {
        return discover(dir, java.util.Collections.<String>emptyList());
    }

    public ProjectInfo discover(File dir, List<String> profiles) throws DiscoverException {
        long t0 = System.currentTimeMillis();
        PomXml pom = new PomXml(profiles);
        ProjectFinder.Found found = new ProjectFinder(profiles).find(dir);
        List<Module> modules = readModules(found, pom);
        long ms = System.currentTimeMillis() - t0;
        return new ProjectInfo(found.selectedDir, found.rootDir, found.rootPom,
                found.relocated, ms, modules);
    }

    private List<Module> readModules(ProjectFinder.Found found, PomXml pom) {
        List<Module> out = new ArrayList<Module>(found.modulePoms.size());
        for (int i = 0; i < found.modulePoms.size(); i++) {
            File f = found.modulePoms.get(i);
            Map<String, String> coord = pom.readCoord(f);
            List<PomXml.DeclaredDep> deps = pom.readDeclared(f);
            List<Module.Declared> declared = new ArrayList<Module.Declared>(deps.size());
            for (PomXml.DeclaredDep d : deps) {
                declared.add(new Module.Declared.Builder()
                        .groupId(d.groupId).artifactId(d.artifactId).version(d.version)
                        .scope(d.scope).optional(d.optional).build());
            }
            out.add(new Module(
                    coord.get("groupId"),
                    coord.get("artifactId"),
                    coord.get("version"),
                    coord.get("packaging"),
                    found.relPaths.get(i),
                    pomPath(found.rootDir, f),
                    deps.size(),
                    pom.readModules(f).size(),
                    declared));
        }
        return out;
    }

    private static String pomPath(File root, File file) {
        try {
            return root.getCanonicalFile().toPath().relativize(file.getCanonicalFile().toPath())
                    .toString().replace(File.separatorChar, '/');
        } catch (IOException e) {
            return root.toPath().toAbsolutePath().normalize()
                    .relativize(file.toPath().toAbsolutePath().normalize())
                    .toString().replace(File.separatorChar, '/');
        }
    }

    /** 读 pom 原文。UTF-8；文件不存在时返回 null（由调用方决定报什么错）。 */
    public static String readPomText(File pomFile) {
        try {
            byte[] bytes = Files.readAllBytes(pomFile.toPath());
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
