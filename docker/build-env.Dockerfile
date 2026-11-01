# 打包构建镜像：JDK 25 + mingw-w64 + 打包工具
#
# 为什么需要这个镜像
# ==================
# 打包要两样东西，基础镜像（eclipse-temurin:25-jdk）都不带：
#   1. 交叉编译 Windows 启动器的 mingw-w64
#   2. rpm / zip / xz / file 和图形库
#
# 装一次进镜像，每次 CI 直接命中（CNB 按 Dockerfile 内容哈希缓存）。
#
# 为什么不用 zig
# ==============
# 之前一直用 zig 交叉编译（`zig cc -target ...`）。但 zig 的发行包
# 解出来是 16000+ 个小文件，而这个构建环境在「tar 展开上万个文件」
# 时会报 Function not implemented (ENOSYS) —— 工作区、/tmp、$HOME、
# /usr/local 全试过，连 docker build 的层里也一样报。
#
# 反证：同一个环境里 apt-get install 装了几十个包、上万个文件，
#       每次都成功（dpkg 走自己的解包路径，不碰 tar）。
#
# 所以改用 apt 装 mingw-w64：
#   - 走 dpkg，绕开 tar 的 ENOSYS
#   - sfx.c 只是普通 C（标准头 + windows.h + unistd.h + LZMA SDK），
#     没有任何 zig 语言特性，mingw 编出的 PE 一样能用
#   - Linux 启动器用本机 gcc 即可，本来就不需要交叉工具链
#
# 代价：Linux 产物绑宿主的 glibc（Ubuntu resolute，较新）。
# 要兼容 Ubuntu 20.04 / Debian 11 时，打包时显式设 ZIG=... 走老路径，
# 见 scripts/pack-linux.sh 的注释。
#
# 为什么不用 dpkg-deb 打 .deb
# ========================
# dpkg-deb --build 内部是 `tar -cf - -C <root> .`，同样会撞上上面的
# ENOSYS。换成 scripts/make-deb.py：纯 Python 标准库（tarfile 生成
# control.tar.xz / data.tar.xz，手写 60 字节 ar 头拼成 ar 归档），
# 全程进程内完成，不 fork tar/ar/dpkg-deb。
#
# 因此镜像里【不需要】dpkg-deb，也不需要 python3-debian 这类第三方库。
# 只要求 python3 带标准库 tarfile + lzma —— 下面的自检会验证。
#
FROM eclipse-temurin:25-jdk

ENV DEBIAN_FRONTEND=noninteractive

# ------------------------------------------------------------------ 工具链
#
# mingw-w64             交叉编译 Windows 启动器（x86_64-w64-mingw32-gcc）
# build-essential/gcc   编译 Linux 启动器
# git                   发版流程要提交版本号、推 release 分支
#                       （scripts/release-prepare.sh）。代码本身是 CNB
#                       用独立 service 拉的，容器里本来没有 git。
# zip unzip             打压缩包
# rpm                   rpmbuild 打 RPM 包（Ubuntu 里包名就叫 rpm）
# xz-utils              解 xz / 造 AppImage
# file                  校验产物类型（pack-*.sh 里的 file | grep GUI）
# fakeroot              rpmbuild 需要它来处理权限（DEB 已不用 dpkg-deb）
#
# 图形库：build.sh 里的 quarkusBuild 会加载应用类，部分类加载路径
# 会触发 Skiko 的 native 初始化，缺 libGL 会 UnsatisfiedLinkError。
# 这些库必须在【宿主】装 —— CNB 里 ./gradlew 跑在容器内，装进镜像正好。
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        ca-certificates curl \
        git \
        python3 \
        build-essential gcc binutils \
        mingw-w64 \
        zip unzip rpm xz-utils file fakeroot \
        libgl1 libglx-mesa0 libgl1-mesa-dri libglu1-mesa \
        libx11-6 libxi6 libxrender1 libxtst6 libxext6 libxrandr2 \
        fontconfig fonts-dejavu-core; \
    ldconfig; \
    rm -rf /var/lib/apt/lists/*

# ------------------------------------------------------------ appimagetool
#
# 预置进镜像，而不是每次打包时现下：
#   - CI 里访问 GitHub Releases 经常超时 / 拿不到 302 后的
#     release-assets.githubusercontent.com，之前每轮都「跳过」
#   - 它 15MB，跟着 Dockerfile 内容哈希一起缓存，下过一次就不再下
#
# Ubuntu 仓库里没有 appimagetool 包（只有 libappimage0 库），
# 所以只能取官方 AppImage 发行版。
#
# 它是 AppImage 格式，容器里跑需要 FUSE（docker build 默认没开）。
# 用 --appimage-extract 展开即可 —— 而且展开后反而更好用：
#   /opt/appimagetool/usr/bin/appimagetool   真二进制（静态链接）
#   /opt/appimagetool/usr/bin/mksquashfs     它要调用的打包器
#
# 注意这两个必须放在一起、保持 usr/bin 相对位置：官方的 AppRun
# 靠 $this_dir/usr/bin 找 mksquashfs，单独拷一个二进制会失败。
#
# 为什么不用 COPY 拿 scripts/install-appimagetool.sh：
#   CNB 的 docker.build 只把 Dockerfile 本身传进 build context，
#   日志里能看到 `load build context / transferring context: 2B`
#   —— 也就是空的。COPY 任何仓库文件都会报
#     "/scripts/xxx.sh": not found
#   所以这里必须自包含，不能依赖 context。
#
# GitHub Actions 那边（没有这个镜像）用的是
# scripts/install-appimagetool.sh，两边的下载/展开步骤保持一致。
ENV APPIMAGETOOL_URL=https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage
RUN set -eux; \
    mkdir -p /opt/appimagetool; \
    curl -fsSL --retry 3 --retry-delay 5 --connect-timeout 20 \
        -o /tmp/appimagetool.AppImage "$APPIMAGETOOL_URL"; \
    chmod +x /tmp/appimagetool.AppImage; \
    cd /tmp; \
    /tmp/appimagetool.AppImage --appimage-extract >/dev/null; \
    cp -a /tmp/squashfs-root/. /opt/appimagetool/; \
    rm -rf /tmp/squashfs-root /tmp/appimagetool.AppImage; \
    test -x /opt/appimagetool/usr/bin/appimagetool; \
    test -x /opt/appimagetool/usr/bin/mksquashfs; \
    ln -sf /opt/appimagetool/usr/bin/appimagetool /usr/local/bin/appimagetool; \
    /usr/local/bin/appimagetool --version

# mksquashfs 也放进 PATH：appimagetool 在部分环境下会优先找
# PATH 里的 mksquashfs，找不到才回落到自带的那份。两条路都铺上。
ENV PATH="/opt/appimagetool/usr/bin:${PATH}"

# ------------------------------------------------------------------ 自检
#
# 构建镜像时就验证一遍：环境不对在这里失败，比跑到打包第 3 步
# 才发现缺东西要好定位得多 —— 每次踩这种坑都要白等一整轮 CI。
#
# 检查清单来自实际使用：把 scripts/*.sh 和 scripts/*.py 里出现的
# 外部命令全列出来逐个验证，缺一个就列出来（不是遇到第一个就退出，
# 这样一次能看到所有缺口，不用来回试）。
#
# 特意做的两件「真事」：
#   1. 真的编一个最小 Windows GUI 程序 —— 光有 x86_64-w64-mingw32-gcc
#      不够，还要 libcomctl32.a（进度条用）。缺了会在这里暴露。
#   2. 真的 import 一遍 lzma/hashlib/struct —— make-sfx.py 靠它们压缩
#      和写索引。都是标准库，但某些精简镜像会把 lzma 裁掉。
#
RUN set -eux; \
    missing=""; \
    for c in \
        java javac jlink \
        gcc strip objdump \
        x86_64-w64-mingw32-gcc \
        python3 \
        bash \
        git \
        zip unzip rpm rpmbuild \
        xz tar gzip file fakeroot \
        appimagetool mksquashfs \
        curl sed awk grep find sort cut tr head tail \
        stat du df mktemp readlink sha256sum \
    ; do \
        command -v "$c" >/dev/null 2>&1 || missing="$missing $c"; \
    done; \
    if [ -n "$missing" ]; then \
        echo "错误：镜像缺少命令:$missing" >&2; \
        exit 1; \
    fi; \
    python3 -c 'import lzma, hashlib, struct; print("python3 标准库 lzma/hashlib/struct 可用")'; \
    python3 -c 'import tarfile, io; assert "xz" in tarfile.TarFile.OPEN_METH; print("python3 tarfile 支持 w:xz（make-deb.py 需要）")'; \
    printf '#include <windows.h>\n#include <commctrl.h>\nint main(void){return 0;}\n' > /tmp/t.c; \
    x86_64-w64-mingw32-gcc -mwindows -lcomctl32 -o /tmp/t.exe /tmp/t.c; \
    file /tmp/t.exe | grep -q "PE32+ executable" || { echo "错误：mingw 产物不是 PE32+" >&2; exit 1; }; \
    rm -f /tmp/t.c /tmp/t.exe; \
    echo "构建环境自检通过"

WORKDIR /workspace
