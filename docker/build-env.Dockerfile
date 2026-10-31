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
FROM eclipse-temurin:25-jdk

ENV DEBIAN_FRONTEND=noninteractive

# ------------------------------------------------------------------ 工具链
#
# mingw-w64             交叉编译 Windows 启动器（x86_64-w64-mingw32-gcc）
# build-essential/gcc   编译 Linux 启动器
# zip unzip             打压缩包
# rpm                   rpmbuild 打 RPM 包（Ubuntu 里包名就叫 rpm）
# xz-utils              解 xz / 造 AppImage
# file                  校验产物类型（pack-*.sh 里的 file | grep GUI）
# fakeroot              rpmbuild / dpkg-deb 需要它来处理权限
#
# 图形库：build.sh 里的 quarkusBuild 会加载应用类，部分类加载路径
# 会触发 Skiko 的 native 初始化，缺 libGL 会 UnsatisfiedLinkError。
# 这些库必须在【宿主】装 —— CNB 里 ./gradlew 跑在容器内，装进镜像正好。
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        ca-certificates curl \
        build-essential gcc binutils \
        mingw-w64 \
        zip unzip rpm xz-utils file fakeroot \
        libgl1 libglx-mesa0 libgl1-mesa-dri libglu1-mesa \
        libx11-6 libxi6 libxrender1 libxtst6 libxext6 libxrandr2 \
        fontconfig fonts-dejavu-core; \
    ldconfig; \
    rm -rf /var/lib/apt/lists/*

# ------------------------------------------------------------------ 自检
#
# 构建镜像时就验证一遍：环境不对在这里失败，比跑到打包第 3 步
# 才发现缺 rpmbuild 要好定位得多。
#
# 特意真的编一个最小的 Windows GUI 程序：光有 x86_64-w64-mingw32-gcc
# 不够，还要 libcomctl32.a（进度条用）之类在。缺了会在这里就暴露。
RUN set -eux; \
    java -version; \
    javac -version; \
    command -v jlink; \
    command -v rpmbuild; \
    command -v zip; \
    command -v file; \
    command -v gcc; \
    x86_64-w64-mingw32-gcc --version | head -1; \
    printf '#include <windows.h>\n#include <commctrl.h>\nint main(void){return 0;}\n' > /tmp/t.c; \
    x86_64-w64-mingw32-gcc -mwindows -lcomctl32 -o /tmp/t.exe /tmp/t.c; \
    file /tmp/t.exe; \
    rm -f /tmp/t.c /tmp/t.exe; \
    echo "构建环境自检通过"

WORKDIR /workspace
