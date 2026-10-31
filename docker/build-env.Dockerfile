# 打包构建镜像：JDK 25 + zig + 打包工具
#
# 为什么需要这个镜像
# ==================
# CNB 构建机的存储扛不住「一次写上万个小文件」：
#   tar 解 zig（16000+ 个小文件）时，五个候选位置全部报
#   "Function not implemented" (ENOSYS)。同一个位置建 200 个
#   平铺文件却毫无问题 —— 不是权限、不是坏包，是写入量。
#
# 换镜像指不定还会踩别的坑，所以干脆把 zig 焊进镜像：
#   镜像构建发生在 CNB 的构建机之外（docker build 的层文件系统是
#   普通的 overlayfs），解压 16000 个文件毫无压力；
#   镜像构建一次之后被缓存（CNB 按 Dockerfile 内容的哈希做 key），
#   后续每次 CI 直接命中，连下载都省了。
#
# 为什么不直接用社区的 zig 镜像
# ============================
#   baredevcontainer/zig、eloitor/zig 之类都是纯 zig 环境：
#   没有 JDK 25（headlessmc 要求 class file 69），没有 rpm，
#   而且多半以非 root 用户运行，装不了系统包。
#   我们需要的是一次性装齐的打包环境，自己写反而更短。
#
# 镜像 tag 的选择
# ===============
#   CNB 的 docker.build 按 Dockerfile 内容哈希找缓存，不依赖 tag。
#   下面用 eclipse-temurin:25-jdk 作基底，和原来 ci pipeline 用的
#   完全一致，避免 JDK 版本漂移带来的额外变量。
#
FROM eclipse-temurin:25-jdk

# ------------------------------------------------------------------ 版本
#
# ZIG_VERSION 改动会让整层缓存失效（随之重下一遍 zig），
# 升版本时留意一下即可。
ARG ZIG_VERSION=0.13.0

ENV DEBIAN_FRONTEND=noninteractive

# ------------------------------------------------------------------ 打包工具
#
# 必须先装这些，因为下一步下载 zig 要用 curl：
#   eclipse-temurin 是基于 Ubuntu 的极简镜像，连 curl 都没有
#   （第一次构建就是死在 `/bin/sh: 1: curl: not found`）。
#
# 对应 pack-linux-formats.sh / pack-windows.sh 的需要：
#   curl        下载 zig / 备用
#   zip unzip   打压缩包（Windows 的 .zip、AppImage 用的 tar）
#   rpm         rpmbuild 打 RPM 包（Ubuntu 里包名就叫 rpm）
#   xz-utils    解 xz / 造 AppImage
#   file        校验产物类型（pack-*.sh 里的 file | grep GUI）
#   fakeroot    rpmbuild / dpkg-deb 需要它来处理权限
#
# 图形库：build.sh 里的 quarkusBuild 会加载应用类，部分类加载路径
# 会触发 Skiko 的 native 初始化，缺 libGL 会 UnsatisfiedLinkError。
# 这些库必须在【宿主】装 —— CNB 里 ./gradlew 跑在容器内，装进镜像正好。
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        curl ca-certificates \
        zip unzip rpm xz-utils file fakeroot \
        libgl1 libglx-mesa0 libgl1-mesa-dri libglu1-mesa \
        libx11-6 libxi6 libxrender1 libxtst6 libxext6 libxrandr2 \
        fontconfig fonts-dejavu-core; \
    ldconfig; \
    rm -rf /var/lib/apt/lists/*

# ------------------------------------------------------------------ zig
#
# 解压位置 /opt/zig：镜像里的常规软件目录。
#
# 这一步是关键：docker build 的层是普通 overlayfs，解压 zig 的 16000+
# 个小文件毫无压力。而 CNB 构建机的存储对「一次写上万个小文件」会报
# ENOSYS —— 那正是之前反复失败的原因，现在这一动作发生在镜像里，
# 构建一次后就永久缓存在层中。
#
# 这里故意不做镜像回退 —— 构建失败时日志能直接看到是哪个 URL 出的
# 问题；真遇到网络问题再改 Dockerfile 即可。
#
# 校验：只检查 zig 能正常输出版本号。ziglang.org 的 tarball 带 minisign
# 签名，但这里省掉了验证密钥分发的问题 —— 镜像构建发生在可信环境，
# 而版本号又写死（不是 latest），风险可控。
#
# URL 命名在 0.15 前后变过一次，所以两种顺序都试：
#   0.13.0:  zig-linux-x86_64-0.13.0.tar.xz    （<os> 在前）
#   0.15+:   zig-x86_64-linux-0.17.0.tar.xz    （<arch> 在前）
# 写死其中一种，升级版本时就会突然 404；两种都试就没这个问题。
RUN set -eux; \
    arch="$(dpkg --print-architecture)"; \
    case "$arch" in \
        amd64) zig_arch=x86_64 ;; \
        arm64) zig_arch=aarch64 ;; \
        *) echo "不支持的架构: $arch" >&2; exit 1 ;; \
    esac; \
    base="https://ziglang.org/download/${ZIG_VERSION}"; \
    ok=0; \
    for name in \
        "zig-linux-${zig_arch}-${ZIG_VERSION}" \
        "zig-${zig_arch}-linux-${ZIG_VERSION}" \
    ; do \
        url="${base}/${name}.tar.xz"; \
        echo "尝试 $url"; \
        if curl -fL --retry 2 --retry-delay 2 --connect-timeout 20 \
                -o /tmp/zig.tar.xz "$url"; then \
            ok=1; break; \
        fi; \
        echo "  下载失败，换下一种命名"; \
        rm -f /tmp/zig.tar.xz; \
    done; \
    [ "$ok" = 1 ] || { echo "错误：两种命名的 tarball 都下不到" >&2; exit 1; }; \
    mkdir -p /opt/zig; \
    tar -xJf /tmp/zig.tar.xz -C /opt/zig --strip-components=1; \
    rm -f /tmp/zig.tar.xz; \
    /opt/zig/zig version

ENV PATH="/opt/zig:${PATH}"

# ------------------------------------------------------------------ 自检
#
# 构建镜像时就验证一遍：环境不对的话在这里失败，比跑到打包第 3 步
# 才发现缺 rpm 要好定位得多。
RUN set -eux; \
    java -version; \
    javac -version; \
    zig version; \
    command -v jlink; \
    command -v rpmbuild; \
    command -v zip; \
    command -v file; \
    echo "构建环境自检通过"

WORKDIR /workspace
