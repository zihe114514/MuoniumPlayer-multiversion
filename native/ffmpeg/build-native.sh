#!/usr/bin/env bash
# Only invoked by recipe.py after source hashes + component names were checked.
set -euo pipefail
: "${NATIVE_WORK:?}" "${NATIVE_PLATFORM:?}" "${NATIVE_ROOT:?}" "${JAVA_HOME:?}"
: "${JAVACPP_JAR:?}" "${FFMPEG_JAVA_JAR:?}"
work=$NATIVE_WORK
prefix=$work/install
sources=$work/sources
jobs=${NATIVE_JOBS:-4}
mkdir -p "$prefix" "$work/openssl-build" "$work/zlib-build" "$work/ffmpeg-build" "$work/jni" "$work/jni-runtime"
exec > >(tee "$work/build.log") 2>&1
printf 'Platform: %s\n' "$NATIVE_PLATFORM"
java -version
make --version
perl --version
python3 --version

cc=gcc
cxx=g++
ar=ar
ranlib=ranlib
strip_tool=strip
openssl_target=
openssl_extra=()
zlib_cross=
ff_target=()
jni_properties=$NATIVE_PLATFORM
jni_extra=()
export CFLAGS='-O2 -fPIC'
export CXXFLAGS='-O2 -fPIC'
export LDFLAGS=
platform_ldflags=

case "$NATIVE_PLATFORM" in
  linux-x86_64)
    [[ $(uname -s) == Linux && $(uname -m) == x86_64 ]] || exit 2
    openssl_target=linux-x86_64
    ff_target+=(--enable-pthreads)
    platform_ldflags='-Wl,-rpath,$ORIGIN'
    ;;
  windows-x86_64)
    # Cross compile with MinGW on Linux. Runtime smoke is a separate Windows CI job.
    [[ $(uname -s) == Linux ]] || exit 2
    cc=x86_64-w64-mingw32-gcc-posix
    cxx=x86_64-w64-mingw32-g++-posix
    ar=x86_64-w64-mingw32-ar
    ranlib=x86_64-w64-mingw32-ranlib
    strip_tool=x86_64-w64-mingw32-strip
    openssl_target=mingw64
    zlib_cross=x86_64-w64-mingw32
    jni_properties=windows-x86_64-mingw
    ff_target+=(--enable-cross-compile --target-os=mingw32 --arch=x86_64 --enable-w32threads
      '--extra-libs=-lws2_32 -lcrypt32 -lbcrypt')
    platform_ldflags='-static-libgcc -static-libstdc++'
    jni_extra+=('-Dplatform.compiler.default=-O2 -static-libgcc -static-libstdc++')
    ;;
  android-arm64)
    : "${ANDROID_NDK_HOME:?Install the locked Android NDK first}"
    [[ $(uname -s) == Linux && $(uname -m) == x86_64 ]] || exit 2
    grep -Eq "Pkg.Revision[[:space:]]*=[[:space:]]*$ANDROID_NDK_REVISION[[:space:]]*$" "$ANDROID_NDK_HOME/source.properties"
    export ANDROID_NDK_ROOT=$ANDROID_NDK_HOME
    tools=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64
    export PATH=$tools/bin:$PATH
    triple=aarch64-linux-android
    arch=aarch64
    openssl_target=android-arm64
    cc=$tools/bin/$triple$ANDROID_API-clang
    cxx=$tools/bin/$triple$ANDROID_API-clang++
    ar=$tools/bin/llvm-ar
    ranlib=$tools/bin/llvm-ranlib
    strip_tool=$tools/bin/llvm-strip
    openssl_extra+=("-D__ANDROID_API__=$ANDROID_API")
    ff_target+=(--enable-cross-compile --target-os=android "--arch=$arch" --enable-pthreads --enable-jni
      --disable-symver "--sysroot=$tools/sysroot")
    platform_ldflags='-Wl,-z,max-page-size=16384'
    jni_extra+=("-Dplatform.sysroot=$tools/sysroot"
      '-Dplatform.compiler.output=-DANDROID -fPIC -shared -Wl,--no-undefined -Wl,-z,max-page-size=16384 -o '
      '-Dplatform.compiler.default=-O2 -static-libstdc++'
      '-Dplatform.link=log#:android#:m#:dl#')
    ;;
  *) echo 'Unsupported platform' >&2; exit 2 ;;
esac
export CC=$cc CXX=$cxx AR=$ar RANLIB=$ranlib STRIP=$strip_tool
"$cc" --version
"$cxx" --version
# A private pkg-config universe prevents accidental host/optional dependencies.
export PKG_CONFIG_PATH=$prefix/lib/pkgconfig
export PKG_CONFIG_LIBDIR=$prefix/lib/pkgconfig
export PKG_CONFIG_SYSROOT_DIR=

# Build only the mandatory external libraries. OpenSSL 3 provider defaults remain linked in.
# Apache 2.0 OpenSSL + FFmpeg --enable-version3; no GPL/nonfree/video third parties.
(
  cd "$work/openssl-build"
  perl "$sources/openssl-3.5.7/Configure" "$openssl_target" --prefix="$prefix" --libdir=lib \
    no-shared no-module no-engine no-legacy no-apps no-tests no-docs no-comp \
    "${openssl_extra[@]}"
  make -j "$jobs"
  make install_sw
)
(
  cd "$work/zlib-build"
  if [[ -n $zlib_cross ]]; then export CHOST=$zlib_cross; fi
  "$sources/zlib-1.3.1/configure" --prefix="$prefix" --static
  make -j "$jobs"
  make install
)
# Record generated JNI against the pinned *release* classes, not regenerated master bindings.
(
  cd "$work/ffmpeg-build"
  flags=()
  while IFS= read -r flag; do flags+=("$flag"); done < "$work/configure-flags.txt"
  "$sources/ffmpeg-8.1.2/configure" "${flags[@]}" "${ff_target[@]}" --prefix="$prefix" \
    "--cc=$cc" "--cxx=$cxx" "--ar=$ar" "--ranlib=$ranlib" "--strip=$strip_tool" \
    "--extra-cflags=$CFLAGS -I$prefix/include" "--extra-cxxflags=$CXXFLAGS -I$prefix/include" \
    "--extra-ldflags=$LDFLAGS -L$prefix/lib $platform_ldflags" --pkg-config-flags=--static
  make -j "$jobs"
  make install
)

common_jni=(-properties "$jni_properties" '-Dplatform.root=/' "-Dplatform.compiler=$cxx"
  "-Dplatform.includepath=$prefix/include" "-Dplatform.linkpath=$prefix/lib" "${jni_extra[@]}")
classpath=$JAVACPP_JAR:$FFMPEG_JAVA_JAR
java -cp "$classpath" org.bytedeco.javacpp.tools.Builder -classpath "$classpath" "${common_jni[@]}" -nodelete \
  -d "$work/jni-runtime" -o jnijavacpp org.bytedeco.javacpp.Pointer \
  org.bytedeco.javacpp.presets.javacpp 'org.bytedeco.javacpp.chrono.*'
classes=()
for module in avutil swresample avcodec avformat swscale; do
  classes+=("org.bytedeco.ffmpeg.$module.*" "org.bytedeco.ffmpeg.global.$module")
done
java -cp "$classpath" org.bytedeco.javacpp.tools.Builder -classpath "$classpath" "${common_jni[@]}" -nodelete -d "$work/jni" "${classes[@]}"

if [[ $NATIVE_PLATFORM == windows-x86_64 ]]; then
  pthread=$($cc -print-file-name=libwinpthread-1.dll)
  [[ -f $pthread ]] || { echo 'Missing required libwinpthread-1.dll' >&2; exit 1; }
  cp "$pthread" "$work/jni/libwinpthread-1.dll"
fi
# Record loader dependencies. Every non-system dependency must be bundled/audited before release.
for file in "$prefix"/lib/* "$prefix"/bin/* "$work"/jni/* "$work"/jni-runtime/*; do
  [[ -f $file ]] || continue
  case "$file" in
    *.so|*.so.*) readelf -d "$file" >> "$work/dependencies.txt" ;;
    *.dll) x86_64-w64-mingw32-objdump -p "$file" >> "$work/dependencies.txt" ;;
    *.dylib) otool -L "$file" >> "$work/dependencies.txt" ;;
  esac
done
