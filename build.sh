#!/bin/sh
# depviz · 构建脚本
#
# 产出 dist/：
#   depviz.jar     可执行 jar（java -jar 直接跑）
#   depviz.sh      启动/停止脚本
#   USAGE.md       使用手册
#   config.json    默认配置（已存在则不覆盖）
#   logs/          运行时日志
#   data/          扫描产物（每个项目一个子目录）
#
# 用法：
#   ./build.sh              完整构建（跳过测试）
#   ./build.sh --test       构建前跑单元测试
#   ./build.sh --clean      只清理 dist/ 与 target/（可与其他参数任意组合，清理后即退出）
#
# 为什么 jar 要复制到 dist/ 而不是留在 target/：dist/ 是「可以整个打包发给别人」
# 的目录 —— 解开就能 ./depviz.sh start。target/ 是 mvn 的工作区，里面还有
# original-depviz.jar、classes/、surefire 报告，不适合直接分发。

set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
cd "$HERE"

DIST="$HERE/dist"
JAR_NAME="depviz.jar"
CONFIG_NAME="config.json"
RUN_TESTS=0
CLEAN_ONLY=0
for arg in "$@"; do
  case "$arg" in
    --test) RUN_TESTS=1 ;;
    --clean) CLEAN_ONLY=1 ;;
    -h|--help)
      sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "未知参数: $arg（试试 --test / --clean / --help）" >&2
      exit 2
      ;;
  esac
done

# 先解析全部参数，避免 --clean 与 --test 的顺序改变结果。
if [ "$CLEAN_ONLY" = "1" ]; then
  rm -rf "$DIST" target
  echo "已清理 $DIST 与 target/"
  exit 0
fi

echo "== 1/4 构建 jar"
if [ "$RUN_TESTS" = "1" ]; then
  mvn -B -o clean package
else
  # -DskipTests 仍会编译测试代码，避免漏掉测试编译错误。
  mvn -B -o clean package -DskipTests
fi

BUILT="$HERE/target/depviz.jar"
if [ ! -f "$BUILT" ]; then
  echo "构建失败：没有产出 $BUILT" >&2
  exit 1
fi

echo "== 2/4 准备 dist/ 目录"
mkdir -p "$DIST/logs" "$DIST/data"

echo "== 3/4 复制 jar、启动脚本与使用手册"
cp "$BUILT" "$DIST/$JAR_NAME"
cp "$HERE/depviz.sh" "$DIST/depviz.sh"
chmod +x "$DIST/depviz.sh"
# USAGE.md 随版本覆盖；用户配置保留。
if [ -f "$HERE/USAGE.md" ]; then
  cp "$HERE/USAGE.md" "$DIST/USAGE.md"
else
  echo "   跳过 USAGE.md（仓库里没有）"
fi

echo "== 4/4 生成默认配置"
# 保留已有配置，避免覆盖用户设置。
if [ -f "$DIST/$CONFIG_NAME" ]; then
  echo "   保留已有 $DIST/$CONFIG_NAME"
else
  # dataDir 留空，跟随 DEPVIZ_HOME；未设置时使用 ~/.depviz。
  # offline=false 保持在线解析，避免漏掉 BOM / build extension。
  # threads 留空以串行扫描，保持模块顺序可复现。
  cat > "$DIST/$CONFIG_NAME" <<'JSON'
{
  "maven": "",
  "javaHome": "",
  "settingsXml": "",
  "localRepo": "",
  "offline": false,
  "profiles": "",
  "xmx": "auto",
  "dataDir": "",
  "logLevel": "info",
  "fileLogLevel": "info",
  "threads": ""
}
JSON
  echo "   已生成 $DIST/$CONFIG_NAME"
fi

cat <<EOF

构建完成：$DIST
  depviz.jar    $(wc -c < "$DIST/$JAR_NAME" | tr -d ' ') 字节
  USAGE.md      使用手册（先看这个）
  config.json   默认配置（dataDir 留空 = 跟随 DEPVIZ_HOME，即 $DIST/data）
  logs/         运行日志
  data/         每个项目一个子目录

启动：  $DIST/depviz.sh start
前台：  $DIST/depviz.sh foreground
停止：  $DIST/depviz.sh stop
状态：  $DIST/depviz.sh status
日志：  $DIST/depviz.sh logs
EOF

