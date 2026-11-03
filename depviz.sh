#!/bin/sh
# depviz · 启动/停止脚本
#
# 用法：
#   ./depviz.sh start          后台启动（日志写 logs/）
#   ./depviz.sh stop           停止
#   ./depviz.sh restart        重启
#   ./depviz.sh status         看是否在跑
#   ./depviz.sh foreground     前台启动（日志直接进终端）
#   ./depviz.sh logs [N]       看日志（默认最后 80 行，N=0 表示持续跟随）
#
# 环境变量：
#   DEPVIZ_PORT       端口（默认 8080；被占用时会自动往后找，见 status 输出）
#   DEPVIZ_JAVA_OPTS  额外的 JVM 参数（如 -Xmx2g）
#   DEPVIZ_HOME       dist 目录（默认本脚本所在目录）
#
# 日志与状态文件，各有各的用途：
#   logs/depviz.log   应用日志（Java 侧 Log 类写，带时间戳与级别）—— 排查用
#   logs/depviz.out   进程 stdout/stderr（Maven 输出、JVM 崩溃栈）—— 启动失败时看这里
#   logs/depviz.pid   进程号（Java 侧写，是系统认识的 pid，见下）—— stop 靠它找进程
#   logs/depviz.url   本次启动的实际地址（Java 侧写）—— start / status 靠它报地址
#
# 后两个由 Java 侧写，不是脚本自己记：Windows 上 Git Bash / MSYS 的 ps、kill
# 用的是它自己的一套进程编号，和任务管理器、taskkill 认的 Windows PID 不是
# 一回事。所以启动时由 Java 把原生 pid 写进 depviz.pid，脚本在 Windows 上按这个
# pid 用 tasklist 查、用 taskkill 停；地址同理，脚本不猜端口、也不解析终端输出
# （Windows 控制台代码页下中文输出按 UTF-8 根本 grep 不到）。Linux / macOS 上
# 照旧用 ps / kill，地址一样从文件读。
#
# 日志级别不在这里传 —— 它在 config.json 的 logLevel / fileLogLevel 里。
# JVM 启动失败时应用日志尚未打开，stdout/stderr 是唯一诊断输出。

set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
PORT_DIR=${DEPVIZ_HOME:-$HERE}
JAR="$PORT_DIR/depviz.jar"
LOGS="$HERE/logs"
DATA="$HERE/data"
PIDFILE="$LOGS/depviz.pid"
URLFILE="$LOGS/depviz.url"
APPLOG="$LOGS/depviz.log"
OUTLOG="$LOGS/depviz.out"
PORT=${DEPVIZ_PORT:-8080}

die() { echo "$*" >&2; exit 1; }

ensure_dirs() {
  mkdir -p "$LOGS" "$DATA"
}

read_pid() {
  [ -f "$PIDFILE" ] || return 1
  pid=$(cat "$PIDFILE" 2>/dev/null | tr -d ' \n')
  case "$pid" in
    ''|*[!0-9]*) return 1 ;;
  esac
  echo "$pid"
}

# 去掉地址文件空白及 Windows CRLF。
read_url() {
  [ -f "$URLFILE" ] || return 1
  url=$(tr -d ' \r\n' < "$URLFILE" 2>/dev/null)
  [ -n "$url" ] || return 1
  echo "$url"
}

# Git Bash/Cygwin 不会自动转换环境变量；Java 需要 Windows 路径，否则配置和数据会写错位置。
# 无 cygpath（Linux/macOS）时原样返回。
to_native() {
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -m "$1"
  else
    printf '%s\n' "$1"
  fi
}

# MSYS 的 ps/kill PID 与 Java 原生 PID 不同；用 cygpath 识别环境并改用 tasklist/taskkill。
is_msys() {
  command -v cygpath >/dev/null 2>&1
}

pid_alive() {
  if is_msys; then
    # tasklist 无结果时会输出 INFO；按 PID 列匹配可忽略它。
    tasklist //FI "PID eq $1" //NH 2>/dev/null \
      | awk -v p="$1" '$2==p {f=1} END {exit !f}'
  else
    kill -0 "$1" 2>/dev/null
  fi
}

pid_image() {
  if is_msys; then
    tasklist //FI "PID eq $1" //NH 2>/dev/null \
      | awk -v p="$1" '$2==p {print tolower($1); exit}'
  else
    ps -p "$1" -o comm= 2>/dev/null
  fi
}

# Windows 专用：按地址文件中的端口用 netstat 查监听进程。
# 返回 0（含无人监听，输出为空）或 2（无法查询/地址不可用）。
# 地址文件尚不存在表示启动中；存在但无人监听表示实例已退出。
port_owner() {
  command -v netstat >/dev/null 2>&1 || return 2
  url=$(read_url) || return 2
  port=${url##*:}
  case "$port" in
    ''|*[!0-9]*) return 2 ;;
  esac
  # 只查 TCP（服务不监听 UDP）；协议名固定，状态名随系统语言变化，所以忽略状态列。
  # 客户端连接的本地端口随机，不会匹配服务端端口。
  netstat -ano 2>/dev/null \
    | awk -v p=":${port}" '
        $1 == "TCP" && $2 ~ (p "$") && $NF ~ /^[0-9]+$/ { print $NF; exit }
      '
}

# PID 可能被复用；确认进程名和 depviz 身份，避免误杀其他进程或误报运行。
# Linux/macOS 检查命令行中的 depviz.jar；Windows 通过地址文件端口确认。
# 返回 0=本实例、1=非本实例、2=身份未知；调用方将 2 视为本实例，因为 pidfile 由我们写入。
is_ours() {
  # ps/tasklist 查不到进程时会失败；忽略退出码以免 set -e 退出。
  img=$(pid_image "$1") || true
  # 读不到程序名只表示身份未知，由调用方结合 pidfile 判断。
  [ -n "$img" ] || return 2
  case "$img" in
    *java*) ;;
    *) return 1 ;;
  esac
  if is_msys; then
    # Windows 的 tasklist 不提供命令行；按地址端口确认，避免 PID 复用后误杀其他 java.exe。
    # 地址文件存在但无人监听表示实例已退出；文件尚未写出表示仍在启动，身份未知。
    owner=$(port_owner) || return 2
    [ -n "$owner" ] || return 1
    [ "$owner" = "$1" ] && return 0
    return 1
  fi
  args=$(pid_args "$1") || return 2
  case "$args" in
    *depviz.jar*) return 0 ;;
    *) return 1 ;;
  esac
}

# Linux/macOS 用命令行识别；Git Bash 由 port_owner 识别。
pid_args() {
  ps -p "$1" -o args= 2>/dev/null
}

# 仅当 PID 存活且身份匹配时视为运行；不匹配就清除过期 pidfile。
# 身份未知时信任 pidfile，避免 stop 失效或重复启动。
running_pid() {
  pid=$(read_pid) || return 1
  pid_alive "$pid" || return 1
  st=0
  is_ours "$pid" || st=$?
  case "$st" in
    0|2) echo "$pid" ;;
    *) rm -f "$PIDFILE"; return 1 ;;
  esac
}

# pidfile 对应进程退出即判定启动失败。
# pidfile 写出前，Linux/macOS 可用系统 PID $!；MSYS 的 $! 与 java.exe PID 不同，因此等待超时兜底。
start_died() {
  if pid=$(read_pid); then
    pid_alive "$pid" && return 1
    return 0
  fi
  if is_msys; then
    return 1
  fi
  kill -0 "$bgpid" 2>/dev/null && return 1
  return 0
}

# Java 写入 taskkill 需要的原生 PID；MSYS 的 $! 不能用于停止 java.exe。
start() {
  ensure_dirs
  [ -f "$JAR" ] || die "找不到 $JAR —— 先跑 ./build.sh"
  if pid=$(running_pid); then
    echo "已经在运行（pid ${pid}，端口见 ./depviz.sh status）"
    return 0
  fi
  rm -f "$PIDFILE" "$URLFILE"
  echo "启动中…（日志 ${APPLOG}）"
  # DEPVIZ_HOME 将配置、数据和日志放在 dist，而非 ~/.depviz；MSYS 环境变量需转成 Windows 路径。
  # shellcheck disable=SC2086
  DEPVIZ_HOME="$(to_native "$PORT_DIR")" nohup java ${DEPVIZ_JAVA_OPTS:-} \
      -jar "$(to_native "$JAR")" \
      serve --port "$PORT" --no-browser \
      --log-dir "$(to_native "$LOGS")" \
      --pid-file "$(to_native "$PIDFILE")" \
      --url-file "$(to_native "$URLFILE")" \
      >> "$OUTLOG" 2>&1 &
  bgpid=$!
  # pidfile 由 Java 写入原生 PID；Windows 上不能用 MSYS 的 $!。

# 等 Java 写出地址后再报告成功；stdout 的中文标记会受 Windows 代码页影响。
# 最多等待 30 秒，Windows 冷启动可能因杀毒扫描耗时。
# 仅依据当前平台可验证的 PID 判断退出，避免把启动中的进程误判为失败。
# Windows 额外等待 2 秒，让 Java 写入 pidfile。
if is_msys; then grace=8; else grace=0; fi
i=0
while [ $i -lt 120 ]; do
  if url=$(read_url) && [ -n "$url" ]; then
    # 若 Java 未写出 pidfile，stop 将无法定位进程；因此不默报成功。
    if pid=$(read_pid); then
      echo "已启动（pid ${pid}）"
    else
      echo "已启动（pid ${bgpid}）"
      echo "警告：pid 文件没写出来（${PIDFILE}），stop 可能找不到这个进程。" >&2
    fi
    echo "地址: $url"
    echo "日志: $APPLOG"
    return 0
  fi
  if [ $i -ge "$grace" ] && start_died; then
    rm -f "$PIDFILE" "$URLFILE"
    echo "启动失败。最后的输出：" >&2
    tail -n 30 "$OUTLOG" >&2
    exit 1
  fi
  i=$((i + 1))
  sleep 0.25
done
  pid=$(read_pid) || pid=$bgpid
  echo "进程在跑（pid ${pid}），但 30 秒内没等到它写出地址文件。" >&2
  echo "看 $OUTLOG 与 $APPLOG 排查。" >&2
  return 1
}

# 仅 Linux/macOS 用 SIGTERM 正常退出；Windows 控制台 Java 不能用 taskkill 温和结束。
# 进程可能在检查后自行退出；忽略 kill 失败，调用方随后确认状态。
signal_term() {
  kill "$1" 2>/dev/null || true
}

signal_kill() {
  if is_msys; then
    # //F 强杀，//T 同时终止 Maven 子进程，避免遗留孤儿占用资源。
    taskkill //F //T //PID "$1" >/dev/null 2>&1 || true
  else
    kill -9 "$1" 2>/dev/null || true
  fi
}

# 最多等待 $2 × 0.25 秒。
wait_gone() {
  n=0
  while [ $n -lt "$2" ]; do
    pid_alive "$1" || return 0
    n=$((n + 1))
    sleep 0.25
  done
  return 1
}

stop() {
  if ! pid=$(running_pid); then
    echo "没有在运行"
    rm -f "$PIDFILE"
    return 0
  fi
  echo "停止中（pid ${pid}）…"
  rm -f "$URLFILE"
  if is_msys; then
    # Windows 强杀不会运行 shutdown hook，也不会取消扫描；taskkill 需带 //T。
    signal_kill "$pid"
    if wait_gone "$pid" 8; then
      rm -f "$PIDFILE"
      echo "已停止（Windows 上为强制结束，正在跑的扫描会被一并终止）"
      return 0
    fi
  else
    # SIGTERM 触发 shutdown hook，取消扫描并记录停止日志。
    signal_term "$pid"
    if wait_gone "$pid" 40; then
      rm -f "$PIDFILE"
      echo "已停止"
      return 0
    fi
    echo "10 秒没退出，强制结束" >&2
    signal_kill "$pid"
    if wait_gone "$pid" 20; then
      rm -f "$PIDFILE"
      echo "已停止"
      return 0
    fi
  fi
  # 进程仍在时保留 pidfile 并报告失败，避免下次启动第二个 JVM 写同一 data/。
  echo "停不掉（pid ${pid} 还在）。" >&2
  echo "用任务管理器结束这个进程，或者手动：taskkill //F //T //PID ${pid}" >&2
  return 1
}

status() {
  if pid=$(running_pid); then
    echo "运行中（pid ${pid}）"
    if [ -f "$APPLOG" ]; then
      echo "最近一条日志：$(tail -n 1 "$APPLOG")"
    fi
    # 从启动时写入的 URL 文件读取地址，避免日志历史报出已失效的端口。
    if url=$(read_url); then
      echo "地址: $url"
    fi
    return 0
  fi
  echo "没有在运行"
  return 1
}

foreground() {
  ensure_dirs
  [ -f "$JAR" ] || die "找不到 $JAR —— 先跑 ./build.sh"
  # 有后台实例时拒绝前台启动，避免共写 data/ 或第二个 JVM 退到新端口后成为孤儿。
  if pid=$(running_pid); then
    die "已经有后台实例在运行（pid ${pid}）。先 ./depviz.sh stop，或者直接用 ./depviz.sh logs 0 看日志"
  fi
  # 前台日志已输出到终端，不传 --log-dir，避免重复记录。
  echo "前台运行，Ctrl-C 停止"
  DEPVIZ_HOME="$(to_native "$PORT_DIR")" exec java ${DEPVIZ_JAVA_OPTS:-} \
      -jar "$(to_native "$JAR")" serve --port "$PORT" --no-browser
}

logs() {
  n=${1:-80}
  [ -f "$APPLOG" ] || die "还没有日志文件（${APPLOG}）"
  if [ "$n" = "0" ]; then
    tail -f "$APPLOG"
  else
    tail -n "$n" "$APPLOG"
  fi
}

case "${1:-start}" in
  start) start ;;
  stop) stop ;;
  restart) stop; start ;;
  status) status ;;
  foreground|fg) foreground ;;
  logs) logs "${2:-80}" ;;
  -h|--help|help)
    sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'
    ;;
  *)
    echo "未知命令: $1（试试 start / stop / restart / status / foreground / logs / help）" >&2
    exit 2
    ;;
esac
