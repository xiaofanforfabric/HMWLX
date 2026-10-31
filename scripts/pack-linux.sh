#!/usr/bin/env bash
#
# Linux 打包：目录版 + 单文件自解压版
#
# 产出：
#   dist/HMWLX-linux-x64/                    目录形态（DEB/RPM 安装包从这里取材）
#   dist/HMWLX-<ver>-linux-x64.run           单文件自解压（对应 Windows 的 .exe）
#   dist/HMWLX-linux-x64.tar.gz              目录的压缩包
#
# 目录结构（和 Windows 包完全一致）：
#
#   HMWLX-linux-x64/
#   ├── HMWLX          原生启动器（C，zig 交叉编译）
#   ├── hmwlx.jar      fat jar（含全部依赖）
#   └── runtime/       jlink 裁出来的 JRE
#
# 为什么放弃 quarkus-app 目录 + shell 脚本：
#
#   老方案把 build/quarkus-app 整个搬过来，启动时由一个 sh 脚本
#   拼接 classpath，顺序还不能错（transformed-bytecode.jar 必须
#   排第一，否则报 NoSuchMethodError）。
#
#   现在改用 fat jar：Manifest 里直接写 Main-Class，不需要拼
#   classpath，也不再有「顺序错了就起不来」的坑。启动器换成 C，
#   和 Windows 共用一套逻辑（launcher/sfx.c）。
#
# 为什么不用 jpackage：
#
#   jpackage 要求 --main-jar 是普通可执行 jar，不理解 Quarkus 的
#   嵌套布局。而且它把 JRE 塞进 app image 的方式不透明，
#   想控制体积和目录结构很难。手工组装反而简单可控。
#
# 用法：
#   ./scripts/pack-linux.sh              完整打包
#   ./scripts/pack-linux.sh --skip-jre   复用已裁好的 runtime（迭代样式时省时间）
#   ./scripts/pack-linux.sh --dir-only   只出目录，不打单文件（给 formats 脚本用）
#
set -euo pipefail

# 脚本在 scripts/ 下，项目根目录是它上一级
ROOT="$(cd "$(dirname "$(readlink -f "$0")")/.." && pwd)"
cd "$ROOT"

APP_NAME="HMWLX"
DIST_DIR="dist"

# 版本号唯一来源：gradle.properties 的 appVersion。
# 同时提供 $FAT_JAR（根据版本拼出的构建产物路径）。
# shellcheck source=./version.sh
source "$ROOT/scripts/version.sh"

OUT_DIR="${DIST_DIR}/${APP_NAME}-linux-x64"
RUN_OUT="${DIST_DIR}/${APP_NAME}-${APP_VERSION}-linux-x64.run"
TAR_OUT="${DIST_DIR}/${APP_NAME}-linux-x64.tar.gz"

SKIP_JRE=false
DIR_ONLY=false
for arg in "$@"; do
    case "$arg" in
        --skip-jre) SKIP_JRE=true ;;
        --dir-only) DIR_ONLY=true ;;
    esac
done

# ---------------------------------------------------------------- 定位 zig
ZIG="${ZIG:-}"
if [[ -z "$ZIG" ]]; then
    if command -v zig >/dev/null 2>&1; then
        ZIG="$(command -v zig)"
    elif [[ -x "$PWD/.tooling/zig/zig-linux-x86_64-0.13.0/zig" ]]; then
        ZIG="$PWD/.tooling/zig/zig-linux-x86_64-0.13.0/zig"
    else
        echo "错误：找不到 zig。请设 ZIG=/path/to/zig" >&2
        echo "  或跑 ./scripts/install-zig.sh 自动装到 .tooling/zig" >&2
        exit 1
    fi
fi

# ---------------------------------------------------------------------------
# 找 JDK 25
#
# 必须 25：headlessmc 是 class file 69（Java 25）编译的。
# 用 17 打出来的 runtime 一启动就 UnsupportedClassVersionError。
# ---------------------------------------------------------------------------
find_jdk25() {
    local candidates=(
        "${HMYLX_JDK25:-}"
        # $JAVA_HOME 必须排前面：CI 镜像（CNB 的 eclipse-temurin:25-jdk、
        # GitHub 的 setup-java）都把 JDK 装在非常规路径，只认这里。
        "${JAVA_HOME:-}"
        /tmp/jdk25/jdk-25.0.5+8
        "$HOME/.jdks/temurin-25"
        /usr/lib/jvm/java-25-openjdk-amd64
        /opt/java/openjdk
        /opt/idea-IU-262.8665.337/jbr
    )
    local c
    for c in "${candidates[@]}"; do
        # jlink 是判定"能否打运行时镜像"的关键，只有完整 JDK 才有。
        # JBR（JetBrains Runtime）是裁过的，没有 jlink，要跳过。
        [[ -n "$c" && -x "$c/bin/jlink" ]] && { echo "$c"; return 0; }
    done
    return 1
}

# ---------------------------------------------------------------- 依赖检查
if [[ ! -f "$FAT_JAR" ]]; then
    echo "错误：找不到 fat jar，请先跑 ./scripts/build.sh 或 ./gradlew fatJar" >&2
    exit 1
fi
echo "==> 版本: $APP_VERSION"
echo "==> fat jar: $(du -h "$FAT_JAR" | cut -f1)"
echo "==> zig: $ZIG ($("$ZIG" version))"

mkdir -p "$DIST_DIR"

# ---------------------------------------------------------------- 编译启动器
#
# 同一个 sfx.c 编两个平台：Windows 版是 PE，Linux 版是 ELF。
# Linux 侧锁定 glibc 2.31，覆盖 Ubuntu 20.04 / Debian 11 及以后。
#
echo "==> 编译 Linux 启动器"
mkdir -p build/launcher
"$ZIG" cc -target x86_64-linux-gnu.2.31 -O2 -s -Wall \
    launcher/sfx.c launcher/third_party/lzma/LzmaDec.c \
    -o build/launcher/HMWLX-linux
STUB="build/launcher/HMWLX-linux"
chmod 755 "$STUB"
echo "    $(du -h "$STUB" | cut -f1)"

# ---------------------------------------------------------------- 组装目录
echo "==> 组装目录 $OUT_DIR"
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

cp "$STUB" "$OUT_DIR/HMWLX"
chmod 755 "$OUT_DIR/HMWLX"

cp "$FAT_JAR" "$OUT_DIR/hmwlx.jar"

# ---------------------------------------------------------------- 换 skiko
#
# fat jar 里的 skiko 原生库是构建机平台的（compose.desktop.currentOs）。
# 在 Linux 上构建本来就对，但如果哪天在别的平台上构建 Linux 包，
# 这一步能保证换回来。统一走同一个脚本，behavior 可预期。
#
echo "==> 确保 skiko 原生库是 Linux 版"
./scripts/skiko-strip.sh "$OUT_DIR/hmwlx.jar" linux-x64

# ---------------------------------------------------------------- 裁剪 JRE
#
# 模块清单是手工列的，不能用 jdeps 自动算 —— jdeps 分析不穿
# Quarkus 的间接引用，算出来会缺模块，运行期才炸。
#
#   java.base          必需
#   java.desktop       Compose Desktop（AWT/Swing）
#   java.logging       Quarkus 日志
#   java.naming        JNDI
#   java.xml           资源解析
#   java.management    JMX 探测
#   java.instrument    字节码增强
#   java.net.http      HttpClient（认证流程）
#   java.security.jgss Kerberos（微软认证链路）
#   jdk.crypto.ec      ECDSA/TLS
#   jdk.crypto.cryptoki PKCS#11
#   jdk.unsupported    sun.misc.Unsafe（Netty/Jackson 依赖）
#   jdk.zipfs          jar 文件系统
#   jdk.localedata     中文本地化，不能省，否则界面中文变方块
#
if [[ "$SKIP_JRE" == "true" && -d "$OUT_DIR/runtime" ]]; then
    echo "==> 复用已有 runtime（--skip-jre）"
else
    JDK="$(find_jdk25)" || {
        echo "错误：找不到带 jlink 的 JDK 25" >&2
        echo "  JBR 是裁剪版，没有 jlink。请安装完整 JDK 25 或设 HMYLX_JDK25。" >&2
        exit 1
    }
    echo "==> jlink 裁剪 JRE（$JDK）"
    rm -rf "$OUT_DIR/runtime"
    "$JDK/bin/jlink" \
        --add-modules java.base,java.desktop,java.logging,java.naming,java.xml,java.management,java.instrument,java.net.http,java.security.jgss,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.unsupported,jdk.zipfs,jdk.localedata \
        --strip-debug \
        --no-header-files \
        --no-man-pages \
        --compress=zip-6 \
        --output "$OUT_DIR/runtime"
    echo "    runtime: $(du -sh "$OUT_DIR/runtime" | cut -f1)"
fi

# ---------------------------------------------------------------- 权限
#
# Gradle 缓存里的文件是 0600（只有属主可读）。自带机器上跑没事，
# 但分发出去后用户读不了，启动即 ClassNotFoundException。
# a+rX 里的 X 只对目录和已有执行位的文件加 x，不会把 jar 误标成可执行。
#
chmod -R a+rX "$OUT_DIR"

# ---------------------------------------------------------------- 校验
for f in HMWLX hmwlx.jar runtime/bin/java runtime/lib/modules; do
    if [[ ! -e "$OUT_DIR/$f" ]]; then
        echo "错误：缺少 $f" >&2
        exit 1
    fi
done
echo "==> 目录体积: $(du -sh "$OUT_DIR" | cut -f1)"

if [[ "$DIR_ONLY" == "true" ]]; then
    echo ""
    echo "=== 完成（仅目录）==="
    echo "目录: $OUT_DIR"
    exit 0
fi

# ---------------------------------------------------------------- 打 tar.gz
echo "==> 打包 tar.gz"
rm -f "$TAR_OUT"
tar -C "$DIST_DIR" -czf "$TAR_OUT" "${APP_NAME}-linux-x64"

# ---------------------------------------------------------------- 单文件
#
# 和 Windows 的 .exe 是同一套机制：LZMA 压缩载荷追加到 ELF 末尾，
# 首次运行解压到 ~/.cache/HMWLX/app/<指纹>/，之后复用。
#
echo "==> 制作单文件自解压版（LZMA，较慢）"
python3 scripts/make-sfx.py \
    --stub "$STUB" \
    --payload-dir "$OUT_DIR" \
    --output "$RUN_OUT"
chmod 755 "$RUN_OUT"

echo ""
echo "=========================================="
echo " 打包完成"
echo "=========================================="
echo " 目录   : $OUT_DIR  ($(du -sh "$OUT_DIR" | cut -f1))"
echo " tar.gz : $TAR_OUT  ($(du -h "$TAR_OUT" | cut -f1))"
echo " 单文件 : $RUN_OUT  ($(du -h "$RUN_OUT" | cut -f1))"
echo ""
echo " 单文件用法:"
echo "   chmod +x $RUN_OUT && ./$RUN_OUT"
echo "   首次运行会解压到 ~/.cache/HMWLX/app/，之后秒开。"
