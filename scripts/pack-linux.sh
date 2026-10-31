#!/usr/bin/env bash
#
# 把 build/quarkus-app 打成 Linux 便携版。
#
# 产出：
#   dist/HMWLX-linux-x64/              ← 目录形态，解压即用
#   dist/HMWLX-linux-x64.tar.gz        ← 上面那个目录的压缩包
#
# 用法：
#   ./scripts/pack-linux.sh             正常打包
#   ./scripts/pack-linux.sh --skip-jre  复用已有的精简 JRE（迭代调样式时省时间）
#
# ---------------------------------------------------------------------------
# 为什么不能直接用 jpackage
# ---------------------------------------------------------------------------
#
# jpackage 对 Quarkus 这种"jar 套 jar"的布局支持不好：
# 它要求 --main-jar 指向一个普通可执行 jar，而 quarkus-run.jar 靠
# Manifest 里的 Class-Path 去 lib/ 下找几百个依赖。jpackage 不会
# 递归搬运这些相对路径引用，打出来的包一跑就是 NoClassDefFoundError。
#
# 所以这里手工组装目录结构，只借用 jlink 裁 JRE：
#
#   HMWLX-linux-x64/
#   ├── HMWLX              ← 启动脚本
#   ├── app/               ← 原样搬过来的 quarkus-app
#   └── runtime/           ← jlink 裁出来的精简 JRE
#
# 这个结构就是"便携版"：解压后双击 HMWLX 即可，删目录即卸载。

set -euo pipefail

# 脚本在 scripts/ 下，项目根目录是它上一级
cd "$(dirname "$(readlink -f "$0")")/.."

APP_NAME="HMWLX"
BUILD_DIR="build/quarkus-app"
DIST_DIR="dist"
OUT_DIR="${DIST_DIR}/${APP_NAME}-linux-x64"
SKIP_JRE=false
[[ "${1:-}" == "--skip-jre" ]] && SKIP_JRE=true

# ---------------------------------------------------------------------------
# 找 JDK 25
#
# 必须 25：headlessmc 是 class file 69（Java 25）编译的。
# 用 17 打出来的 runtime 一启动就 UnsupportedClassVersionError。
# ---------------------------------------------------------------------------
find_jdk25() {
    local candidates=(
        "${HMYLX_JDK25:-}"
        /tmp/jdk25/jdk-25.0.5+8
        /opt/idea-IU-262.8665.337/jbr
        "$HOME/.jdks/temurin-25"
        /usr/lib/jvm/java-25-openjdk-amd64
    )
    local c
    for c in "${candidates[@]}"; do
        # jlink 是判定"能否打运行时镜像"的关键，只有完整 JDK 才有。
        # JBR（JetBrains Runtime）是裁过的，没有 jlink，要跳过。
        [[ -n "$c" && -x "$c/bin/jlink" ]] && { echo "$c"; return 0; }
    done
    return 1
}

JDK="$(find_jdk25)" || {
    echo "❌ 找不到带 jlink 的 JDK 25" >&2
    echo "   JBR 是裁剪版，没有 jlink。请安装完整 JDK 25 或设 HMYLX_JDK25。" >&2
    exit 1
}
echo "JDK: $JDK ($("$JDK/bin/java" -version 2>&1 | head -1))"

if [[ ! -d "$BUILD_DIR" ]]; then
    echo "❌ 找不到 $BUILD_DIR，请先跑 ./scripts/build.sh" >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# 1. jlink 裁 JRE
#
# 模块清单是手工列出来的，不是 jdeps 算的 —— jdeps 分析不穿 Quarkus 的
# Class-Path 间接引用，算出来的结果会缺模块，运行期才炸。
#
# 每个模块的必要性：
#   java.base         必需
#   java.desktop      Compose Desktop（AWT/Swing）+ Browser.kt 的 Desktop
#   java.logging      Quarkus 的日志体系
#   java.naming       JNDI，Quarkus 的配置/资源查找会用到
#   java.xml          Compose 资源解析、Quarkus 的 XML 处理
#   java.management   JMX，Quarkus 启动会探测
#   java.instrument   字节码增强（Quarkus 的 transformed-bytecode）
#   java.net.http     Java 11 HttpClient，认证流程用
#   java.security.jgss  Kerberos，微软认证链路上可能出现
#   jdk.crypto.ec     ECDSA/TLS，连微软和 Mojang 必须
#   jdk.crypto.cryptoki PKCS#11，TLS provider 的备选
#   jdk.unsupported   sun.misc.Unsafe —— 大量库（Netty/Jackson）依赖它
#   jdk.zipfs         jar 文件系统，Quarkus 加载嵌套 jar 要用
#   jdk.localedata    中文本地化。**不能省**，否则界面中文变方块
# 注意：--strip-debug 去掉调试符号（省约 20M），
#      --compress=zip-6 压缩（JDK 21+ 的语法，比旧版 compress=2 更省）。
# ---------------------------------------------------------------------------
if [[ "$SKIP_JRE" == "true" && -d "$OUT_DIR/runtime" ]]; then
    echo "⏭  复用已有 runtime（--skip-jre）"
else
    echo "→ jlink 裁剪 JRE…"
    rm -rf "$OUT_DIR/runtime"
    "$JDK/bin/jlink" \
        --add-modules java.base,java.desktop,java.logging,java.naming,java.xml,java.management,java.instrument,java.net.http,java.security.jgss,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.unsupported,jdk.zipfs,jdk.localedata \
        --strip-debug \
        --no-header-files \
        --no-man-pages \
        --compress=zip-6 \
        --output "$OUT_DIR/runtime"
    echo "   runtime 大小: $(du -sh "$OUT_DIR/runtime" | cut -f1)"
fi

# ---------------------------------------------------------------------------
# 2. 拷贝应用
#
# 原样搬 quarkus-app：里面的 Manifest Class-Path 是相对路径
# （lib/main/xxx.jar），整体搬过去相对关系不变，不用改任何配置。
# ---------------------------------------------------------------------------
echo "→ 拷贝应用…"
rm -rf "$OUT_DIR/app"
cp -r "$BUILD_DIR" "$OUT_DIR/app"

# 统一权限（必须做，否则分发给别人后直接不可用）
#
# Gradle 从缓存里拷出来的 jar 是 0600（只有属主可读）。在自己机器上跑没事，
# 但打包发出去后：
#   - DEB：非 root 用户读不了 jar，启动即 ClassNotFoundException
#   - AppImage：appimagetool 直接拒绝构建，报 "has mode 0600,
#     but it must be readable for all users"
#
# 用 a+rX 而不是 a+r：X 只对**目录**和**已有执行位的文件**加 x，
# 不会把 jar 误标成可执行。
chmod -R a+rX "$OUT_DIR/app"
chmod 755 "$OUT_DIR/HMWLX" 2>/dev/null || true

# ---------------------------------------------------------------------------
# 3. 生成启动脚本
# ---------------------------------------------------------------------------
echo "→ 生成启动脚本…"
cat > "$OUT_DIR/HMWLX" <<'LAUNCHER'
#!/bin/sh
#
# HMWLX 启动脚本。
#
# 用脚本而不是 jpackage 生成的二进制启动器：Quarkus 的类加载器需要
# 正确的 classpath 顺序（transformed-bytecode.jar 必须优先），
# 一个小脚本比调试 jpackage 的启动器配置直接得多。

set -e

# 解析自身所在目录，允许从任意 cwd 启动（比如桌面快捷方式）。
SELF_DIR=$(cd "$(dirname "$0")" && pwd)

# 便携版原则：所有路径都相对程序目录，绝不写用户主目录。
# 这样解压到 U 盘也能用，删目录就干净卸载。
export JAVA_HOME="$SELF_DIR/runtime"
JAVA="$SELF_DIR/runtime/bin/java"

if [ ! -x "$JAVA" ]; then
    echo "错误：找不到运行时 $JAVA" >&2
    echo "请确认 runtime/ 目录完整（可能是解压不完整）" >&2
    exit 1
fi

# Skiko 的原生库在 app/lib/main 里被打进 jar，运行时会解压到临时目录。
# 显式指定 java.io.tmpdir 到一个可写位置，避免某些受限环境下无家可归。
#
# 这些参数与 build.gradle 里 run 任务的配置保持一致，缺一个就会出问题：
#   --add-opens java.base/java.lang   Quarkus 的 thread-local-reset
#                                     在 JDK 24+ 上不补就会 IllegalAccessError
#   --add-opens java.base/java.nio    Netty / Jackson 的堆外访问
#   --add-opens java.base/jdk.internal.misc
#                                     sun.misc.Unsafe 的替代路径
#   --enable-native-access=ALL-UNNAMED Skiko 通过 JNI 调 Skia
#   -Dskiko.renderApi=SOFTWARE        Linux 上没有可靠 GPU 时走软件渲染，
#                                     不设会在部分显卡驱动上直接崩
# ---------------------------------------------------------------------------
# classpath 顺序（关键，改错就起不来）
# ---------------------------------------------------------------------------
#
# 绝对不能写成 `-jar quarkus-run.jar`。原因：
#
#   quarkus-run.jar 的 Main-Class 是 QuarkusEntryPoint，它会去找
#   `@QuarkusMain` 标注的类 —— 而 headlessmc 的 HeadlessMcMain 标了它，
#   于是启动的是 headlessmc 的交互式命令行，不是我们的 GUI。
#   表现为程序卡在一个 `> ` 提示符上等输入。
#
# 所以必须显式指定主类 org.cn.xiaofan.MainKt，并自己拼 classpath。
#
# 顺序规则（与 build.gradle 的 run/test 任务完全一致）：
#   1. transformed-bytecode.jar  Quarkus 改写过的类（给 bean 补无参构造器）
#   2. generated-bytecode.jar    ApplicationImpl 与 _Bean/_ClientProxy
#   3. lib/boot/*.jar            引导器（DisabledInitialContextManager 等）
#   4. lib/main/*.jar            业务依赖
#   5. app/*.jar                 我们自己的类
#
# 1、2 必须最前：同名类要解析到增强版。顺序错了会报
#   NoSuchMethodError: DefaultConfigFileProvider: <init>() not found
#   （因为拿到的是未增强的原版）
#
# app/*.jar 放最后：它里面有被 Quarkus 改写坏的 MainKt（注入了重复的
# main 方法），前面有正确版本时就不会被加载到。
build_cp() {
    local d="$SELF_DIR"
    printf '%s' "$d/app/quarkus/transformed-bytecode.jar:$d/app/quarkus/generated-bytecode.jar"
    local f
    for f in "$d"/app/lib/boot/*.jar; do printf ':%s' "$f"; done
    for f in "$d"/app/lib/main/*.jar; do printf ':%s' "$f"; done
    for f in "$d"/app/app/*.jar; do printf ':%s' "$f"; done
}

CP=$(build_cp)

exec "$JAVA" \
    -XX:+UseSerialGC \
    -Dfile.encoding=UTF-8 \
    -Djava.awt.headless=false \
    -Dskiko.renderApi=SOFTWARE \
    --add-opens java.base/java.lang=ALL-UNNAMED \
    --add-opens java.base/java.nio=ALL-UNNAMED \
    --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
    --enable-native-access=ALL-UNNAMED \
    -cp "$CP" \
    org.cn.xiaofan.MainKt \
    "$@"
LAUNCHER
chmod +x "$OUT_DIR/HMWLX"

# ---------------------------------------------------------------------------
# 4. 打 tar.gz
# ---------------------------------------------------------------------------
echo "→ 打包 tar.gz…"
tar -C "$DIST_DIR" -czf "${DIST_DIR}/${APP_NAME}-linux-x64.tar.gz" "${APP_NAME}-linux-x64"

echo ""
echo "=== 完成 ==="
echo "目录 : $OUT_DIR  ($(du -sh "$OUT_DIR" | cut -f1))"
echo "压缩 : ${DIST_DIR}/${APP_NAME}-linux-x64.tar.gz  ($(du -sh "${DIST_DIR}/${APP_NAME}-linux-x64.tar.gz" | cut -f1))"
echo ""
echo "试用 : $OUT_DIR/HMWLX"
