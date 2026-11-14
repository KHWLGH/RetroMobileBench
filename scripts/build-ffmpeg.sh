#!/bin/bash
# Reproducible LGPL-only decoder build. Run from repository root in Git Bash.
set -eu
# Native Windows make needs drive paths; inherit this in configure's subshells.
pwd() { cygpath -m "$PWD"; }
export -f pwd
root="$(pwd)"
ndk="$root/.tools/android-ndk-r10e"
make="$ndk/prebuilt/windows-x86_64/bin/make.exe"
for abi in armeabi-v7a x86; do
  if [ "$abi" = armeabi-v7a ]; then
    arch=arm; api=8; triple=arm-linux-androideabi; tool=arm-linux-androideabi
    flags='-march=armv7-a -mfpu=vfpv3-d16 -mfloat-abi=softfp'
  else
    arch=x86; api=9; triple=i686-linux-android; tool=x86
    flags='-march=i686 -mstackrealign'
  fi
  prefix="$ndk/toolchains/$tool-4.9/prebuilt/windows-x86_64/bin/$triple-"
  build="$root/app/build/ffmpeg/$abi"
  mkdir -p "$build"
  cd "$build"
  if [ "${1:-}" != --resume ] || [ ! -f config.h ]; then
  bash "$root/third_party/ffmpeg/configure" --target-os=android --arch="$arch" \
    --enable-cross-compile --cc="${prefix}gcc.exe" --cxx="${prefix}g++.exe" \
    --ar="${prefix}ar.exe" --ranlib="${prefix}ranlib.exe" --nm="${prefix}nm.exe" \
    --strip="${prefix}strip.exe" --sysroot="$ndk/platforms/android-$api/arch-$arch" \
    --prefix="$build/install" --disable-everything --disable-autodetect \
    --enable-decoder=h264,h263,mpeg4,vp8 --enable-parser=h264,h263,mpeg4video,vp8 \
    --enable-demuxer=mov,matroska --enable-protocol=file --disable-network \
    --disable-programs --disable-doc --disable-avdevice --disable-avfilter \
    --disable-swscale --disable-swresample --disable-postproc --disable-asm \
    --disable-gpl --disable-nonfree --enable-shared --disable-static --enable-pic \
    --disable-symver --disable-debug --disable-stripping --enable-small \
    --extra-cflags="$flags -O2 -fPIC" --extra-ldflags='-Wl,-z,noexecstack'
  fi
  options=(-f "$root/third_party/ffmpeg/Makefile" "prefix=$build/install")
  "$make" "${options[@]}" -j8
  # Quiet INSTALL prints the entire header list twice and exceeds cmd.exe's 8191-char limit.
  "$make" "${options[@]}" V=1 install
  cd "$root"
done
