#!/usr/bin/env bash
# 一次性：把构建工具链装到 ./tools（Linux x64 / macOS 均可；只需网络）
set -e
cd "$(dirname "$0")/.."
mkdir -p tools && cd tools
[ -d jdk17 ] || {
  echo "== JDK17 (temurin)"
  OS=$(uname -s); ARCH=$(uname -m)
  case "$OS$ARCH" in Linuxx86_64) P=linux/x64;; Linuxaarch64) P=linux/aarch64;; Darwinx86_64|Darwinarm64) P=mac/$( [ "$ARCH" = arm64 ] && echo aarch64 || echo x64 );; *) echo "手动装 JDK17"; exit 1;; esac
  curl -sSL -o jdk.tgz "https://api.adoptium.net/v3/binary/latest/17/ga/$P/jdk/hotspot/normal/eclipse"
  tar xzf jdk.tgz && mv jdk-17* jdk17 && rm jdk.tgz; }
[ -d android-sdk/build-tools/34.0.0 ] || {
  echo "== Android build-tools 34"
  mkdir -p android-sdk/build-tools
  curl -sSL -o bt.zip https://dl.google.com/android/repository/build-tools_r34-linux.zip
  unzip -q bt.zip -d btX && mv btX/android-14 android-sdk/build-tools/34.0.0 && rm -rf bt.zip btX; }
[ -d android-sdk/platforms/android-34 ] || {
  echo "== Android platform 34"
  mkdir -p android-sdk/platforms
  curl -sSL -o p.zip https://dl.google.com/android/repository/platform-34-ext7_r02.zip
  unzip -q p.zip -d pX && mv pX/android-34 android-sdk/platforms/android-34 && rm -rf p.zip pX; }
echo "工具链就绪：tools/{jdk17, android-sdk}"
