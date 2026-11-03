#!/bin/sh
# 用 Java CLI 图数据核对生产引擎的计数、路径与模块版本仲裁。
set -eu
cd "$(dirname "$0")/.."

if [ ! -f target/depviz.jar ]; then
  echo "target/depviz.jar 不存在，先跑 mvn -B -o -DskipTests package" >&2
  exit 2
fi

CHECK_DIR=$(mktemp -d "${TMPDIR:-/tmp}/depviz-engine.XXXXXXXX")
cleanup() {
  python3 - "$CHECK_DIR" <<'PY'
from pathlib import Path
import shutil
import sys
import tempfile
path = Path(sys.argv[1]).resolve()
if path.parent != Path(tempfile.gettempdir()).resolve() or not path.name.startswith('depviz-engine.'):
    raise SystemExit('拒绝清理非预期目录: ' + str(path))
shutil.rmtree(path)
PY
}
trap cleanup EXIT
export DEPVIZ_HOME="$CHECK_DIR/home"

for name in demo multi; do
  java -jar target/depviz.jar graph "src/test/resources/projects/$name" \
    --tree "src/test/resources/tree/$name.txt" -o "$CHECK_DIR/$name.json" > /dev/null
done

node - "$CHECK_DIR/demo.json" "$CHECK_DIR/multi.json" <<'JS'
const fs = require('fs');
const vm = require('vm');
const sandbox = { console };
sandbox.window = sandbox;
vm.createContext(sandbox);
vm.runInContext(fs.readFileSync('src/main/resources/static/js/engine.js', 'utf8'), sandbox);
const E = sandbox.Engine;
const [demo, multi] = process.argv.slice(2).map(path => E.build(JSON.parse(fs.readFileSync(path, 'utf8'))));
let failures = 0;
let checks = 0;
function equal(label, actual, expected) {
  checks++;
  const ok = JSON.stringify(actual) === JSON.stringify(expected);
  if (!ok) failures++;
  console.log((ok ? 'OK   ' : 'FAIL ') + label + ': ' + JSON.stringify(actual) +
    (ok ? '' : ' expected ' + JSON.stringify(expected)));
}
const netty = demo.gaKey.indexOf('io.netty:netty-common');
equal('demo occurrence 数', demo.occ.length, 451);
equal('demo GA 数', demo.gas.length, 208);
equal('搜索 netty', E.search(demo, 'netty', {}).totalGas, 36);
equal('demo 多版本坐标', E.conflicts(demo, '').total, 10);
equal('netty-common 路径数', E.pathsFor(demo, [netty], { effectiveOnly: false }).total, 5);
equal('netty-common 子图节点', E.subgraph(demo, [netty], [], { effectiveOnly: false }).nodes.length, 6);
equal('netty-common 直接上游', E.nodeSummary(demo, netty, { effectiveOnly: false }).upstream.length, 3);

// 多模块期望值逐条依据 tree/multi.txt 核对。
equal('多模块 occurrence 数', multi.occ.length, 19);
equal('多模块 GA 数', multi.gas.length, 13);
equal('多模块生效 occurrence 数', multi.occ.filter(o => o.e).length, 15);
equal('模块根与 POM 身份保持 Maven 顺序', multi.modules.map(m => m.pomPath),
  ['lib/pom.xml', 'pom.xml', 'app/util/pom.xml', 'app/pom.xml']);
const conflicts = E.conflicts(multi, '').conflicts;
equal('多模块冲突坐标数', conflicts.length, 2);
const slf4j = conflicts.find(row => row.ga === 'org.slf4j:slf4j-api');
equal('slf4j 各模块分别胜出', slf4j.winners.map(v => [v.version, v.winModuleIds])
  .sort((a, b) => a[0].localeCompare(b[0])), [['1.7.36', [0]], ['2.0.13', [3]]]);
equal('slf4j 旧版本在两个模块被取代', slf4j.losers.map(v => [v.version, v.moduleIds]),
  [['1.6.0', [0, 3]]]);
equal('slf4j 显示为各模块版本并存', slf4j.splitWinner, true);
const slf4jPaths = E.pathsFor(multi, [slf4j.gaId], { effectiveOnly: false });
equal('slf4j 所有路径与生效路径数',
  [slf4jPaths.total, E.pathsFor(multi, [slf4j.gaId], { effectiveOnly: true }).total], [5, 2]);
const oldSlf4j = E.explainVersion(multi, slf4j.gaId, '1.6.0', {});
equal('slf4j 按模块比较深度和胜出版本', oldSlf4j.moduleRows.map(row =>
  [row.moduleId, row.mineDepth, row.theirDepth, row.theirVersion, row.verdict]),
  [[3, 4, 2, '2.0.13', 'lose'], [0, 3, 2, '1.7.36', 'lose']]);
const choice = conflicts.find(row => row.ga === 'fixture.deps:choice');
const tie = E.explainVersion(multi, choice.gaId, '2.0', {});
equal('choice 同深度由先声明的路径胜出', tie.moduleRows.map(row =>
  [row.moduleId, row.mineOccId, row.theirOccId, row.mineDepth, row.theirDepth, row.verdict]),
  [[3, 18, 16, 3, 3, 'tie']]);
let crossModulePairs = 0;
let unpairedVersions = 0;
let pairedVersions = 0;
let sameDepthVerdicts = 0;
for (let gi = 0; gi < multi.gas.length; gi++) {
  for (const version of multi.gas[gi].v) {
    const detail = E.explainVersion(multi, gi, version, { moduleIds: [] });
    if (!detail || !detail.isLoser) continue;
    sameDepthVerdicts += detail.moduleRows.filter(row => row.verdict === 'tie').length;
    if (!detail.loserPath || !detail.winnerPath) {
      unpairedVersions++;
      continue;
    }
    pairedVersions++;
    if (detail.loserPath.moduleId !== detail.winnerPath.moduleId) crossModulePairs++;
  }
}
equal('版本解释的路径对不跨模块', crossModulePairs, 0);
equal('未生效版本都有同模块胜出路径', unpairedVersions, 0);
equal('可比较的未生效版本数', pairedVersions, 2);
equal('同深度先声明胜出的仲裁数', sameDepthVerdicts, 1);
console.log(checks + ' checks, ' + failures + ' failures');
process.exit(failures ? 1 : 0);
JS
