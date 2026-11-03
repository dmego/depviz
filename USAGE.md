# depviz 使用说明

depviz 是一个本地运行的 Maven 依赖分析工具。它调用 Maven 的 `dependency:tree` 生成依赖树，再把结果整理成可以点击的图，用来查看依赖来源、版本冲突和模块之间的关系。

## 运行环境

- JDK 8 或更高版本。`java` 要在 `PATH` 里，或者把 `JAVA_HOME` 指给 depviz。
- Maven 3.2.5 或更高版本。depviz 会自己调用 `mvn`；装在 `PATH` 里最省事，装在别处也可以在设置里指定路径。

depviz 不会构建项目，也不会修改项目文件。扫描时只执行 `mvn dependency:tree -Dverbose` 并读取输出。

## 启动

发行包解压后直接运行启动脚本：

```bash
unzip depviz-*.zip -d depviz
cd depviz
./depviz.sh start
```

启动完成后打开终端里显示的地址，默认是 `http://127.0.0.1:8080`。端口被占用时，会从 8080 开始依次往后找，最多试到 8089；实际地址以 `status` 或启动日志为准。

`depviz.sh` 的常用命令：

```bash
./depviz.sh status       # 运行状态、实际地址和最后一条日志
./depviz.sh logs 200     # 最近 200 行应用日志
./depviz.sh logs 0       # 持续输出日志，Ctrl-C 退出
./depviz.sh restart
./depviz.sh stop
./depviz.sh foreground   # 前台运行，日志直接输出到终端
```

如果已经有 jar，也可以直接启动：

```bash
java -jar depviz.jar                      # 启动服务并打开浏览器
java -jar depviz.jar serve --port 9000    # 指定端口
java -jar depviz.jar serve --no-browser   # 不打开浏览器，适合远程或无桌面环境
```

从源码构建：

```bash
./build.sh              # 打包到 dist/，跳过测试
./build.sh --test       # 先运行测试再打包
```

## 选择项目

首页提供三种选择项目的方式：

1. 点“选择 Maven 项目目录”，在页面里的目录浏览器中逐层选择。
2. 在路径输入框中填写项目路径，按回车。
3. 通过地址栏打开 `?project=/path/to/project`。

选到子模块也可以。depviz 会向上查找带 `<modules>` 的 reactor 根目录，按整个项目扫描。

路径输入框支持 Windows 盘符，例如 `D:\code\demo`。目录浏览器左侧会列出盘符，`D:` 和 `D:\` 两种写法都能识别。

## 查看依赖图

扫描完成后会进入浏览视图。已经扫描过的项目会保存图数据（`data/<项目名>/graph.json`），重启服务后仍然可以直接打开，不会重新运行 Maven。从首页的“最近打开”或顶部的项目菜单都能进入。

需要更新数据时，点顶栏右侧的“重新扫描”（⟳）。这是唯一会重新执行 Maven 的操作。

默认不自动重扫有两个原因：多模块项目联网扫描可能要几十秒到几分钟，而依赖图的查询都在浏览器里完成，旧图仍然可以查看；Maven 输出的依赖树本身也会有少量差异，例如同一个依赖的 `scope not updated to compile` 注记可能出现在不同行，两次结果不容易直接比较。

界面分为几个区域：

| 区域 | 用途 |
|---|---|
| 顶部搜索 | 按 `groupId:artifactId` 的子串匹配依赖，`⌘K` 或 `Ctrl-K` 聚焦。子串没有结果时，会再按字母子序列匹配，例如 `ncm` 可以找到 `netty-common`。 |
| 左侧“路径” | 选中依赖后，列出从模块根出发的全部引入路径，按深度排序。点某一条路径，图上会高亮。 |
| 左侧“冲突” | 显示同一坐标的多个版本：哪个版本生效、哪个被取代、原因是什么。“仅被引入、未生效”可以展开查看说明。 |
| 画布 | 初始不绘制节点（两万个节点全部绘制会卡），选中依赖后才按需渲染子图。`#btn-fit` 适配窗口，`#btn-focus` 聚焦。 |
| 右侧详情 | 节点的版本、来源和被仲裁掉的依赖；模块根还会显示打包方式、POM 原文和声明的依赖。 |

画布上方的过滤器：

- “只看生效”默认打开，隐藏被仲裁掉的节点。关闭后可以看到声明了但没有生效的依赖。
- “隐藏 test / provided”默认打开。如果项目的大部分依赖都是 test scope，保持默认打开会让图清爽很多。
- 模块选择器只统计选中的模块。多模块项目中，同一个依赖在不同模块下可能得到不同结论。

## 设置

点顶栏的齿轮图标，或首页的“高级设置”，可以修改配置。保存后写入 `config.json`，从下一次扫描开始生效。

| 设置 | 键 | 说明 |
|---|---|---|
| Maven 路径 | `maven` | 留空时使用 `PATH` 里的 `mvn`。可以填 `bin/mvn`、`bin\mvn.cmd`，也可以填整个安装目录，depviz 会自动往下找 `bin/`。 |
| JAVA_HOME | `javaHome` | 留空时继承当前环境。 |
| settings.xml | `settingsXml` | 透传 `-s`。 |
| 本地仓库 | `localRepo` | 透传 `-Dmaven.repo.local`。 |
| 离线模式 | `offline` | 默认关闭（在线）。打开后完全不访问远程仓库。 |
| Profiles | `profiles` | 逗号分隔，每个 profile 分别传给 `-P`。 |
| 并行度 | `threads` | 传给 Maven 的 `-T` 参数，控制并行构建的线程数。留空或填 `1`、`off` 时串行执行。 |
| 堆上限 | `xmx` | 给 Maven 子进程注入 `-Xmx`。默认 `auto`，按物理内存分档：小于 1G 不注入，1–1.5G 为 384m，1.5–2G 为 512m，2–4G 为 768m，大于 4G 为 1024m；设为 `off` 时不注入。 |
| 数据目录 | `dataDir` | 扫描产物的根目录。使用发行包时默认为 `DEPVIZ_HOME/data`，直接运行 jar 时为 `~/.depviz`。 |
| 控制台日志级别 | `logLevel` | `debug` / `info`（默认）/ `warn` / `error`。 |
| 文件日志级别 | `fileLogLevel` | `logs/depviz.log` 的级别，默认 `info`。 |

`config.json` 也可以直接用编辑器修改，改完需要重启服务。升级不会清理不认识的键。如果 `MAVEN_OPTS` 里已经设置了 `-Xmx`，depviz 不会覆盖它。

## 命令行

除了 Web 界面，还提供三个命令，适合脚本或单独排查：

```bash
# 只扫描一次，产出 graph.json，不启动服务
java -jar depviz.jar scan <dir> -o graph.json

# 扫描一次并打印统计
java -jar depviz.jar graph <dir> --stats

# 对已有的 graph.json 出统计，不运行 Maven
java -jar depviz.jar graph graph.json --stats
```

`scan` 默认读取配置里的在线/离线设置，可以用 `--offline` 或 `--online` 覆盖。`graph` 支持 `--tree <tree.txt>`（使用已有的依赖树）、`-o <out.json>`（输出结果）和 `--stats`（打印统计）。

统计输出类似这样：

```
occ        451
roots      1
ga         208
effective  211
conflict   36
duplicate  204
maxDepth   8
```

命令行也会读取 `config.json`，其中配置的 Maven 路径、`JAVA_HOME` 和 profiles 都会生效。

## 文件位置

使用发行包时，启动脚本默认会把 `DEPVIZ_HOME` 指向脚本所在的 `dist/` 目录，日志、配置和数据都在该目录下；直接运行 jar 时，数据目录是 `~/.depviz/`。

```
dist/
  depviz.jar        可执行 jar
  depviz.sh         启动/停止脚本
  USAGE.md          本文件
  config.json       配置
  logs/
    depviz.log      应用日志（带时间戳与级别，超过 8 MB 滚一份 .1）
    depviz.out      进程 stdout/stderr，启动失败时看这里
    depviz.pid      pid 文件
    depviz.url      本次启动的实际地址（start 用它判断服务是否启动成功）
  data/
    projects.json   最近打开的项目（只有元数据）
    <项目名>/
      graph.json    图数据（浏览器拉的就是它）
      maven.log     本次扫描 Maven 的原始输出，全量（超过 8 MB 截断）
      tree.txt      本次的依赖树
      .owner        这个目录归哪个项目所有，防止同名项目互相覆盖
    jobs/<jobId>/   正在进行的扫描的临时目录，扫完就删
```

- `data/<项目名>/` 里的 `maven.log` 和 `tree.txt` 用于排查，扫描失败也会保留。
- 页面上的失败面板会直接给出 `maven.log` 的绝对路径，点开就能看。
- 同名但不同路径的两个项目不会互相覆盖：后一个会使用 `<项目名>-<8位hash>`。
- 在最近列表里删除项目只会删掉记录，不会删除磁盘上的文件。要彻底删掉，需要自己删目录。

## 排查问题

排查时按以下顺序查看：页面上的错误 → `data/<项目名>/maven.log` → `logs/depviz.log` → `logs/depviz.out`。

### 扫描失败

失败面板会显示失败原因、`maven.log` 的完整路径和最后 200 行 Maven 输出。

| 现象 | 原因与处理 |
|---|---|
| `Non-resolvable import POM` / `Cannot access … in offline mode` | 离线模式下依赖没有缓存。点“联网重试”，或关闭设置里的离线模式。 |
| `The POM for … is missing, no dependency information available` | 依赖坐标写错，或私有仓库需要凭证。在设置里配置 `settingsXml`，带上认证信息。 |
| `Maven 退出码 1` | 项目本身构建失败。先在命令行运行 `mvn -B dependency:tree -Dverbose`，查看完整报错。 |
| `Maven 超过 600 秒未结束，已终止` | 网络太慢或项目太大。更换网络、配置镜像，或用 `-T` 并行。 |
| `Cannot run program …: CreateProcess error=193`（Windows） | `mvn` 指向的是无扩展名的 shell 脚本。设置里填 `mvn.cmd`，或填 Maven 安装目录，depviz 会自动找 `.cmd`。 |
| `启动 Maven 失败: No such file or directory` | Maven 不在 `PATH` 里。设置里填绝对路径。 |
| `解析依赖树失败: 第 N 行无法解析: …` | 遇到不认识的树行格式。`tree.txt` 和 `maven.log` 都在 `data/<项目名>/` 下，可以直接查看第 N 行。 |

如果页面出现“N 个依赖未能解析”的黄色提示，说明 Maven 输出里确实有缺少 POM 的依赖（`no dependency information available`）。这些依赖的子树不会出现在图中，图是不完整的。点“联网重试”通常可以补上。插件版本没写之类的 Maven 模型警告不计入这个数量。

### 打不开页面或端口冲突

```bash
./depviz.sh status          # 查看实际地址
./depviz.sh logs 200        # 应用日志
tail -50 logs/depviz.out    # JVM 级别的报错
```

端口 8080 被占用时，服务会依次尝试 8081、8082 等端口，`status` 显示实际使用的端口。

### 重启后项目还在，但图看不了

`data/<项目名>/graph.json` 被删除，或者 `dataDir` 改过。点进项目会进入确认页，再点一次“开始分析”即可重新生成。

### 日志级别

默认是 `info`，只记录启动、扫描和错误。需要查看接口调用和耗时时，把 `config.json` 里的 `logLevel` 改成 `debug` 后重启，或者在设置面板中修改。`fileLogLevel` 单独控制 `logs/depviz.log`，设为 `debug` 后日志会详细很多，一天可能生成多个 8 MB 的日志文件，排查完记得改回 `info`。
