#!/usr/bin/env bash
#
# 把 fat jar 里的 skiko 原生库替换成指定平台的。
#
# 为什么需要这个东西：
#
#   build.gradle 里写的是 `compose.desktop.currentOs`，它按【构建机】
#   挑平台的 skiko 原生库。在 Linux 上构建，jar 里就只有
#   libskiko-linux-x64.so。
#
#   于是打 Windows 包时，Windows 上会崩：
#     org.jetbrains.skiko.LibraryLoadException:
#       Cannot find skiko-windows-x64.dll.sha256
#
#   打 macOS 包同理。
#
# 做法：从 Maven Central 下目标平台的 skiko-awt-runtime-<平台>，
# 用它里面的原生文件替换 jar 根目录下的旧文件。
#
# 用法：
#   ./scripts/skiko-strip.sh <fat.jar> <平台> [输出.jar]
#
# 平台取值：
#   windows-x64  windows-arm64  linux-x64  linux-arm64  macos-x64  macos-arm64
#
# 不给输出路径时原地修改。
#
# 注意：
#   - 只碰 jar 根目录下的原生库文件，不动 META-INF/MANIFEST.MF
#     （启动器用 -jar 启动，全靠它的 Main-Class）
#   - icudtl.dat 是 Skia 的 ICU 数据，Windows 和 Linux 都需要，
#     macOS 不需要（它用系统 ICU）。这里统一按「该平台包里有什么
#     就给什么」处理，不自己猜。
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

JAR="${1:-}"
PLATFORM="${2:-}"
OUT_JAR="${3:-$JAR}"

if [[ -z "$JAR" || -z "$PLATFORM" ]]; then
  echo "用法: $0 <fat.jar> <平台> [输出.jar]" >&2
  echo "平台: windows-x64 windows-arm64 linux-x64 linux-arm64 macos-x64 macos-arm64" >&2
  exit 1
fi
[[ -f "$JAR" ]] || { echo "错误：$JAR 不存在" >&2; exit 1; }

# ---------------------------------------------------------------- 版本号
#
# 从 gradle 的依赖树里读，而不是写死 —— 升级 Compose 后版本会变，
# 写死的话下次就悄悄对不上了。
SKIKO_VERSION="${SKIKO_VERSION:-}"
if [[ -z "$SKIKO_VERSION" ]]; then
  SKIKO_VERSION="$(./gradlew -q :dependencies --configuration runtimeClasspath 2>/dev/null \
    | grep -oE 'org\.jetbrains\.skiko:skiko:[0-9.]+' \
    | head -1 | cut -d: -f3)"
fi
if [[ -z "$SKIKO_VERSION" ]]; then
  echo "错误：读不到 skiko 版本号，请设 SKIKO_VERSION 环境变量" >&2
  exit 1
fi
echo "==> skiko $SKIKO_VERSION / 目标平台 $PLATFORM"

# ---------------------------------------------------------------- 下载
#
# 为什么不用单个 repo1.maven.org：CI 节点经常吃 429。
# Maven Central 对出口 IP 做并发限流，共享构建机的流量一大就把整个
# 打包卡死 —— 日志里就是连续 4 次 curl: (22) ... 429。
#
# 所以按顺序试多个镜像，第一个成功的赢：
#   1. $SKIKO_MIRROR_BASE  显式指定（可给多个，空格分隔，结尾不要 /）
#   2. repo1.maven.org     Maven Central 官方
#   3. maven.aliyun.com    阿里云，国内最快，CI 上通常不经 429
#   4. repo.maven.apache.org  官方备用域名，限流策略与 repo1 独立
#
# 每次尝试后校验文件大小（> 1MB），防止把 429 的 HTML 错误页
# 当成 jar 存下来 —— 那样会在后面 unzip 时谜之失败。
#
CACHE="dist/cache"
mkdir -p "$CACHE"
SKIKO_JAR="$CACHE/skiko-$PLATFORM-$SKIKO_VERSION.jar"
SKIKO_PATH="org/jetbrains/skiko/skiko-awt-runtime-$PLATFORM/$SKIKO_VERSION/skiko-awt-runtime-$PLATFORM-$SKIKO_VERSION.jar"

MIRRORS=()
if [[ -n "${SKIKO_MIRROR_BASE:-}" ]]; then
  # shellcheck disable=SC2206  # 故意按空白拆分
  MIRRORS+=(${SKIKO_MIRROR_BASE})
fi
MIRRORS+=(
  "https://repo1.maven.org/maven2"
  "https://maven.aliyun.com/repository/public"
  "https://repo.maven.apache.org/maven2"
)

MIN_BYTES=1000000

if [[ -f "$SKIKO_JAR" ]] && [[ "$(stat -c%s "$SKIKO_JAR" 2>/dev/null || echo 0)" -gt "$MIN_BYTES" ]]; then
  echo "==> 使用已缓存的 skiko-$PLATFORM-$SKIKO_VERSION.jar"
else
  echo "==> 下载 skiko-awt-runtime-$PLATFORM"
  ok=0
  for base in "${MIRRORS[@]}"; do
    url="${base%/}/$SKIKO_PATH"
    echo "    ← $base"

    # 429 是「你太快了」，等一会再试比立刻换镜像更有效。
    # 其它错误（404/域名不通）就立刻换。
    for attempt in 1 2 3; do
      if curl -fL -sS --connect-timeout 20 --max-time 300 \
              -o "$SKIKO_JAR.part" "$url" 2>/tmp/skiko-curl.$$.log; then
        if [[ "$(stat -c%s "$SKIKO_JAR.part" 2>/dev/null || echo 0)" -gt "$MIN_BYTES" ]]; then
          mv "$SKIKO_JAR.part" "$SKIKO_JAR"
          ok=1
          break 2
        fi
        echo "      文件太小（可能是错误页），换镜像"
        break
      else
        # curl -f 的报错行形如：
        #   curl: (22) The requested URL returned error: 429
        # 用 -w 拿不到 4xx 的码（-f 会让 curl 直接返回 22），
        # 所以从 stderr 里抠。抠不到就当 0，按普通失败处理。
        code="$(grep -oE 'error: [0-9]{3}' /tmp/skiko-curl.$$.log 2>/dev/null \
                | grep -oE '[0-9]{3}' | head -1)"
        if [[ "${code:-0}" == "429" ]] && [[ $attempt -lt 3 ]]; then
          wait_s=$((attempt * 5))
          echo "      429 限流，${wait_s}s 后重试（第 $attempt/3 次）"
          sleep "$wait_s"
          continue
        fi
        echo "      失败（HTTP ${code:-?}），换镜像"
        break
      fi
    done
    rm -f "$SKIKO_JAR.part" /tmp/skiko-curl.$$.log
  done

  if [[ "$ok" -ne 1 ]]; then
    rm -f /tmp/skiko-curl.$$.log
    echo "错误：所有镜像都拿不到 skiko-$PLATFORM。" >&2
    echo "  试过的：${MIRRORS[*]}" >&2
    echo "  可以用 SKIKO_MIRROR_BASE=<前缀> 指定镜像。" >&2
    exit 1
  fi
fi

# ---------------------------------------------------------------- 解包
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
unzip -q "$SKIKO_JAR" -d "$TMP"

# 收集该平台提供的原生文件（根目录下，跳过 META-INF）
mapfile -t NEW_FILES < <(cd "$TMP" && find . -maxdepth 1 -type f \
  \( -name '*.dll' -o -name '*.so' -o -name '*.dylib' -o -name '*.sha256' -o -name 'icudtl.dat' \) \
  -printf '%f\n' | sort)

if [[ ${#NEW_FILES[@]} -eq 0 ]]; then
  echo "错误：$SKIKO_JAR 里没找到任何原生库" >&2
  exit 1
fi
echo "    提供: ${NEW_FILES[*]}"

# ---------------------------------------------------------------- 替换
#
# 先把 jar 里所有「平台原生库 + 哈希 + icudtl」删干净，再放入目标平台的。
# 这样从 Linux 构建的 jar 切到 Windows/macOS 都不会残留。
#
# 注意 set -u 下空数组展开会报错，用 ${arr[@]+"${arr[@]}"} 兜住。
#
echo "==> 清理 jar 内旧的原生库"
OLD_FILES=()
while IFS= read -r line; do
  [[ -n "$line" ]] && OLD_FILES+=("$line")
done < <(unzip -l "$JAR" \
  | awk '{print $NF}' \
  | grep -E '^(libskiko-[^/]*\.(so|dylib)|skiko-[^/]*\.dll|icudtl\.dat)(\.sha256)?$' || true)

if [[ ${#OLD_FILES[@]} -gt 0 ]]; then
  echo "    删除: ${OLD_FILES[*]}"
  # -q 静默，-d 删除；文件不存在时 zip 会报错，用 || true 兜住
  zip -qd "$JAR" "${OLD_FILES[@]}" 2>/dev/null || true
else
  echo "    （jar 内没有原生库，跳过）"
fi

if [[ "$OUT_JAR" != "$JAR" ]]; then
  cp "$JAR" "$OUT_JAR"
fi

# zip 要在解包目录里执行（用相对文件名），所以目标路径先转成绝对路径
case "$OUT_JAR" in
  /*) ABS_OUT="$OUT_JAR" ;;
  *)  ABS_OUT="$ROOT/$OUT_JAR" ;;
esac

echo "==> 嵌入 $PLATFORM 的原生库"
( cd "$TMP" && zip -q -X "$ABS_OUT" "${NEW_FILES[@]}" )

# ---------------------------------------------------------------- 校验
fail=0
for f in "${NEW_FILES[@]}"; do
  if ! unzip -l "$OUT_JAR" | grep -qF "$f"; then
    echo "错误：$OUT_JAR 内缺少 $f" >&2
    fail=1
  fi
done

# 不应该残留别的平台
if unzip -l "$OUT_JAR" | awk '{print $NF}' \
   | grep -E '^(libskiko-[^/]*\.(so|dylib)|skiko-[^/]*\.dll)$' \
   | grep -vFf <(printf '%s\n' "${NEW_FILES[@]}") > /dev/null 2>&1; then
  echo "警告：jar 内仍有非目标平台的原生库" >&2
  unzip -l "$OUT_JAR" | awk '{print $NF}' \
    | grep -E '^(libskiko-[^/]*\.(so|dylib)|skiko-[^/]*\.dll)$' | sed 's/^/    /'
fi

# MANIFEST 必须还在，否则 -jar 启动会失败
if ! unzip -p "$OUT_JAR" META-INF/MANIFEST.MF 2>/dev/null | grep -qi 'Main-Class'; then
  echo "错误：$OUT_JAR 内 MANIFEST 缺失或没有 Main-Class" >&2
  fail=1
fi

[[ $fail -eq 0 ]] || exit 1

echo "==> 完成: $OUT_JAR ($(du -h "$OUT_JAR" | cut -f1))"
