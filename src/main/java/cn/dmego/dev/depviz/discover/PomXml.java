package cn.dmego.dev.depviz.discover;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只做一件事的 pom 读取器：拿 {@code <modules>}、拿坐标、拿 {@code <dependencies>}。
 *
 * <p><b>安全</b>：所有解析都禁用 DTD 与外部实体（XXE）。项目 pom 里出现
 * {@code <!DOCTYPE} 时绝不能去联网取 DTD —— 那既慢又是攻击面。
 */
final class PomXml {

    private final DocumentBuilderFactory factory;
    private final java.util.Set<String> enabledProfiles = new java.util.HashSet<String>();
    private final java.util.Set<String> disabledProfiles = new java.util.HashSet<String>();

    PomXml() {
        this(java.util.Collections.<String>emptyList());
    }

    PomXml(List<String> profiles) {
        for (String entry : profiles) {
            for (String value : entry.split(",")) {
                String id = value.trim();
                if (id.startsWith("!") || id.startsWith("-")) {
                    disabledProfiles.add(id.substring(1));
                } else {
                    enabledProfiles.add(id.startsWith("+") ? id.substring(1) : id);
                }
            }
        }
        factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setValidating(false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://javax.xml.XMLConstants/feature/secure-processing", true);
        } catch (ParserConfigurationException ignored) {
            // 老解析器可能不认识某个 feature —— 已经关掉 XInclude 与实体展开，够用
        }
    }

    boolean looksLikeAggregator(File pomFile) {
        return !readModules(pomFile).isEmpty();
    }

    /**
     * 读 {@code <modules>}；pom 本身**语法坏掉**时抛异常而不是静默返回空列表。
     *
     * <p>「静默返回空」会让上层把坏 pom 当成「没有子模块的单模块项目」，
     * 于是 `/api/discover` 报 `moduleCount: 1`、随后 Maven 才报错 —— 用户看着
     * 「发现成功但扫描失败」，根因被藏起来了。实测缺一个闭合标签的 pom 就是这样。
     */
    List<String> readModulesStrict(File pomFile) throws DiscoverException {
        Document doc = parse(pomFile);
        if (doc == null) {
            throw new DiscoverException("pom.xml 不是合法 XML: " + pomFile.getAbsolutePath());
        }
        return modulesOf(doc, pomFile);
    }

    List<String> readModules(File pomFile) {
        Document doc = parse(pomFile);
        if (doc == null) {
            return new ArrayList<String>();
        }
        return modulesOf(doc, pomFile);
    }

    private List<String> modulesOf(Document doc, File pomFile) {
        List<String> out = new ArrayList<String>();
        Element root = doc.getDocumentElement();
        addModules(firstChild(root, "modules"), out);
        for (Element profile : activeProfiles(root)) {
            addModules(firstChild(profile, "modules"), out);
        }
        Map<String, String> props = modelProperties(pomFile, root, new java.util.HashSet<String>());
        for (int i = 0; i < out.size(); i++) {
            out.set(i, resolveProps(out.get(i), props));
        }
        return out;
    }

    private static void addModules(Element modules, List<String> out) {
        if (modules == null) {
            return;
        }
        NodeList list = modules.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            Node n = list.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && "module".equals(n.getNodeName())) {
                String v = n.getTextContent().trim();
                if (!v.isEmpty()) {
                    out.add(v);
                }
            }
        }
    }

    /** 同一 POM 有显式激活的 profile 时，Maven 不再激活其 activeByDefault profile。 */
    private List<Element> activeProfiles(Element root) {
        List<Element> selected = new ArrayList<Element>();
        List<Element> defaults = new ArrayList<Element>();
        Element profiles = firstChild(root, "profiles");
        if (profiles == null) {
            return selected;
        }
        NodeList list = profiles.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            Node n = list.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE || !"profile".equals(n.getNodeName())) {
                continue;
            }
            Element profile = (Element) n;
            String id = text(firstChild(profile, "id"));
            if (disabledProfiles.contains(id)) {
                continue;
            }
            if (enabledProfiles.contains(id)) {
                selected.add(profile);
            } else if ("true".equalsIgnoreCase(text(
                    firstChild(firstChild(profile, "activation"), "activeByDefault")))) {
                defaults.add(profile);
            }
        }
        return selected.isEmpty() ? defaults : selected;
    }

    /** 读 {@code groupId/artifactId/version/packaging}，缺失的返回空串。 */
    Map<String, String> readCoord(File pomFile) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        Document doc = parse(pomFile);
        if (doc == null) {
            return m;
        }
        Element root = doc.getDocumentElement();
        Map<String, String> props = modelProperties(pomFile, root, new java.util.HashSet<String>());
        m.put("groupId", resolveProps(props.get("project.groupId"), props));
        m.put("artifactId", resolveProps(props.get("project.artifactId"), props));
        m.put("version", resolveProps(props.get("project.version"), props));
        String packaging = text(firstChild(root, "packaging"));
        m.put("packaging", packaging.isEmpty() ? "jar" : packaging);
        return m;
    }

    /** 只解析本地 parent、属性及已选择 profile；不改写 declared[] 中的原始声明。 */
    private Map<String, String> modelProperties(File pomFile, Element root,
                                               java.util.Set<String> seen) {
        Map<String, String> props = new LinkedHashMap<String, String>();
        String identity;
        try {
            identity = pomFile.getCanonicalPath();
        } catch (java.io.IOException e) {
            identity = pomFile.getAbsolutePath();
        }
        if (seen.size() >= 24 || !seen.add(identity)) {
            return props;
        }
        Element parent = firstChild(root, "parent");
        if (parent != null) {
            Element relative = firstChild(parent, "relativePath");
            String path = relative == null ? "../pom.xml" : text(relative);
            if (!path.isEmpty()) {
                File parentPom = new File(pomFile.getParentFile(), path);
                if (parentPom.isDirectory()) {
                    parentPom = new File(parentPom, "pom.xml");
                }
                Document doc = parse(parentPom);
                if (doc != null) {
                    Map<String, String> inherited = modelProperties(
                            parentPom, doc.getDocumentElement(), seen);
                    for (Map.Entry<String, String> entry : inherited.entrySet()) {
                        String key = entry.getKey();
                        if (!key.equals("project.groupId") && !key.equals("pom.groupId")
                                && !key.equals("project.artifactId") && !key.equals("pom.artifactId")
                                && !key.equals("project.version") && !key.equals("pom.version")
                                && !key.startsWith("project.parent.")) {
                            props.put(key, resolveProps(entry.getValue(), inherited));
                        }
                    }
                }
            }
            props.put("project.parent.groupId", text(firstChild(parent, "groupId")));
            props.put("project.parent.artifactId", text(firstChild(parent, "artifactId")));
            props.put("project.parent.version", text(firstChild(parent, "version")));
        }
        props.putAll(propertiesOf(root));
        for (Element profile : activeProfiles(root)) {
            props.putAll(propertiesOf(profile));
        }
        String groupId = text(firstChild(root, "groupId"));
        String version = text(firstChild(root, "version"));
        if (parent != null) {
            if (groupId.isEmpty()) {
                groupId = text(firstChild(parent, "groupId"));
            }
            if (version.isEmpty()) {
                version = text(firstChild(parent, "version"));
            }
        }
        props.put("project.groupId", groupId);
        props.put("pom.groupId", groupId);
        props.put("project.artifactId", text(firstChild(root, "artifactId")));
        props.put("pom.artifactId", text(firstChild(root, "artifactId")));
        props.put("project.version", version);
        props.put("pom.version", version);
        return props;
    }

    /** {@code <properties>} 里的键值对。 */
    private static Map<String, String> propertiesOf(Element root) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        Element props = firstChild(root, "properties");
        if (props == null) {
            return out;
        }
        NodeList list = props.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            Node n = list.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                out.put(n.getNodeName(), n.getTextContent().trim());
            }
        }
        return out;
    }

    /**
     * 反复替换 {@code ${key}}，最多 5 轮（属性可以引用属性）。
     *
     * <p>替换不了的占位原样保留 —— 猜一个值比留着占位更糟：它会悄悄把一个模块标成
     * 另一个模块的坐标。
     */
    private static String resolveProps(String raw, Map<String, String> props) {
        if (raw == null || raw.indexOf("${") < 0) {
            return raw;
        }
        String cur = raw;
        for (int round = 0; round < 5 && cur.indexOf("${") >= 0; round++) {
            StringBuilder sb = new StringBuilder();
            int i = 0;
            boolean changed = false;
            while (i < cur.length()) {
                int open = cur.indexOf("${", i);
                int close = open < 0 ? -1 : cur.indexOf('}', open + 2);
                if (open < 0 || close < 0) {
                    sb.append(cur.substring(i));
                    break;
                }
                sb.append(cur, i, open);
                String key = cur.substring(open + 2, close);
                String val = props.get(key);
                if (val == null) {
                    sb.append(cur, open, close + 1);
                } else {
                    sb.append(val);
                    changed = true;
                }
                i = close + 1;
            }
            cur = sb.toString();
            if (!changed) {
                break;
            }
        }
        return cur;
    }

    /**
     * 读直接声明依赖。{@code optional} 保留**原始字符串**（{@code ""} 或 {@code "true"}），
     * 因为前端 {@code views.js:688} 用的是 {@code d.optional === 'true'} 严格比较。
     */
    List<DeclaredDep> readDeclared(File pomFile) {
        List<DeclaredDep> out = new ArrayList<DeclaredDep>();
        Document doc = parse(pomFile);
        if (doc == null) {
            return out;
        }
        Element deps = firstChild(doc.getDocumentElement(), "dependencies");
        if (deps == null) {
            return out;
        }
        NodeList list = deps.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            Node n = list.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE || !"dependency".equals(n.getNodeName())) {
                continue;
            }
            Element d = (Element) n;
            out.add(new DeclaredDep(
                    text(firstChild(d, "groupId")),
                    text(firstChild(d, "artifactId")),
                    text(firstChild(d, "version")),
                    text(firstChild(d, "scope")),
                    text(firstChild(d, "optional"))));
        }
        return out;
    }

    // -------------------------------------------------------------- 内部

    private Document parse(File pomFile) {
        try {
            DocumentBuilder b = factory.newDocumentBuilder();
            b.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            // 默认的 Xerces ErrorHandler 会往 stderr 直接打英文 "[Fatal Error] pom.xml:1:36: ..."。
            // 我们自己的报错是中文的，两套混在一起既吓人又对不上；这里把解析器的输出收掉，
            // 由调用方按 catch 到的异常统一成一句中文。
            b.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override
                public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
                    throw e;
                }

                @Override
                public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
                    throw e;
                }

                @Override
                public void warning(org.xml.sax.SAXParseException e) {
                    // 警告不影响解析结果，丢掉
                }
            });
            return b.parse(pomFile);
        } catch (Exception e) {
            return null;
        }
    }

    private static Element firstChild(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        NodeList list = parent.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            Node n = list.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && name.equals(n.getNodeName())) {
                return (Element) n;
            }
        }
        return null;
    }

    private static String text(Element e) {
        return e == null ? "" : e.getTextContent().trim();
    }

    /** 一条 {@code <dependency>} 声明。 */
    static final class DeclaredDep {
        final String groupId;
        final String artifactId;
        final String version;
        final String scope;
        final String optional;

        DeclaredDep(String groupId, String artifactId, String version, String scope, String optional) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.scope = scope;
            this.optional = optional;
        }
    }
}
