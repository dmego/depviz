#!/bin/sh
# 一键检查：构建/单测、黄金数字、端到端/并发/异步回归、引擎语义与硬约束。
# 浏览器检查需 Python Playwright 与 Chrome；Maven 需本地缓存 dependency:3.6.0/JUnit 4.13.2。
# 显式检查关键输出和退出码，避免旧 jar 或被吞输出造成误判。
set -u

cd "$(dirname "$0")/.." || exit 1
MVN=${MVN:-mvn}
PYTHON=${PYTHON:-python3}
fail=0

step() {
    printf '\n=== %s ===\n' "$1"
}

step "1/8 清理构建"
build_status=0
$MVN -B -o clean package -DskipTests > /tmp/depviz-build.log 2>&1 || build_status=$?
if [ "$build_status" -ne 0 ] || ! grep -q "BUILD SUCCESS" /tmp/depviz-build.log; then
    echo "构建失败，最后 20 行："
    tail -20 /tmp/depviz-build.log
    exit 1
fi
# 防止编译失败后误用 target/ 中的旧 jar。
if [ ! -f target/depviz.jar ]; then
    echo "没有产出 target/depviz.jar"
    exit 1
fi
echo "ok  target/depviz.jar $(wc -c < target/depviz.jar | tr -d ' ') 字节"

step "2/8 单元测试"
test_status=0
$MVN -B -o test > /tmp/depviz-test.log 2>&1 || test_status=$?
line=$(grep -E "^\[(INFO|ERROR)\] Tests run: [0-9]+, Failures:" /tmp/depviz-test.log | tail -1)
echo "${line:-（没有找到测试汇总行）}"
case "$line" in
    *"Failures: 0, Errors: 0, Skipped: 0"*)
        if [ "$test_status" -ne 0 ]; then
            echo "Maven 测试进程退出码非零"
            fail=1
        fi
        ;;
    *) echo "测试未全绿"; fail=1 ;;
esac

step "3/8 黄金数字（固定输入 → 固定输出）"
out=$(java -jar target/depviz.jar graph src/test/resources/projects/demo \
        --tree src/test/resources/tree/demo.txt --stats 2>&1)
printf '%s\n' "$out"
for want in "occ:451" "ga:208" "conflict:36" "duplicate:204"; do
    label=${want%:*}
    value=${want#*:}
    printf '%s\n' "$out" | grep -Eq "^${label}[[:space:]]+${value}$" \
        || { echo "统计字段 $label 应为 $value"; fail=1; }
done

step "4/8 端到端（自己起服务、自己触发扫描）"
if $PYTHON tools/e2e_check.py > /tmp/depviz-e2e.log 2>&1; then
    tail -3 /tmp/depviz-e2e.log
    echo "ok  $(grep -c '^\[OK' /tmp/depviz-e2e.log) 项"
else
    echo "端到端未通过，失败项："
    grep '^\[FAIL' /tmp/depviz-e2e.log
    tail -20 /tmp/depviz-e2e.log
    fail=1
fi

step "5/8 后端身份、任务隔离与 Origin 回归"
if $PYTHON tools/backend_regression.py > /tmp/depviz-backend-regression.log 2>&1; then
    tail -1 /tmp/depviz-backend-regression.log
else
    tail -30 /tmp/depviz-backend-regression.log
    fail=1
fi

step "6/8 前端异步、配置与交互回归"
if $PYTHON tools/frontend_regression.py > /tmp/depviz-frontend-regression.log 2>&1; then
    tail -3 /tmp/depviz-frontend-regression.log
else
    tail -30 /tmp/depviz-frontend-regression.log
    fail=1
fi

step "7/8 前端引擎语义交叉检查"
if sh tools/engine_crosscheck.sh; then
    echo "ok  Java 图数据与生产查询引擎的预期语义一致"
else
    fail=1
fi

step "8/8 前端 DOM id 引用"
$PYTHON tools/check_dom_ids.py || fail=1

printf '\n'
if [ "$fail" -eq 0 ]; then
    echo "全部通过"
else
    echo "有失败项，见上"
fi
exit "$fail"
