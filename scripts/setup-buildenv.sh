#!/usr/bin/env bash
# Build environment for LineageOS 16.0 (Android 9) on Ubuntu 22.04.
#
# Android 9 dates from 2018 and expects tools that are partly missing or
# renamed on current distributions. This script sets them up and reports
# honestly what was not available, instead of silently carrying on.
set -u

log()  { printf '\n=== %s ===\n' "$1"; }
warn() { printf '  !! %s\n' "$1"; }

export DEBIAN_FRONTEND=noninteractive

log "Package sources"
apt-get update -qq || { warn "apt-get update failed"; exit 1; }

log "Basic tools"
apt-get install -y -qq \
    bc bison build-essential ccache curl flex git gnupg gperf \
    imagemagick lib32readline-dev lib32z1-dev liblz4-tool \
    libncurses5 libncurses5-dev libsdl1.2-dev libssl-dev \
    libxml2 libxml2-utils lzop pngcrush rsync schedtool \
    squashfs-tools xsltproc zip unzip zlib1g-dev \
    g++-multilib gcc-multilib python3 python3-pip \
    openssh-server sudo nano wget     git-lfs 2>&1 | tail -3

# Without git-lfs the chromium-webview prebuilts fail in repo sync with
# "Cannot initialize work tree" - the error message does not mention LFS.
#
# IMPORTANT: git-lfs must be set up BEFORE the first repo sync. If it is
# installed afterwards and caught up with --force-sync, git resets the work
# tree from the objects it already has - and those contain the LFS pointers,
# not the contents. The tree then looks complete but holds 133-byte text
# files instead of 232 MB APKs, and the build still completes without error.
# Check it after the sync: the WebView prebuilt (vendor/.../webview.apk) must
# be about 232 MB, not about 133 bytes.
git lfs install --system >/dev/null 2>&1 && echo "  git-lfs set up"

log "Java 8 (strictly required by Android 9)"
if apt-get install -y -qq openjdk-8-jdk 2>&1 | tail -2; then
    :
fi
if command -v javac >/dev/null 2>&1; then
    java -version 2>&1 | head -1 | sed 's/^/  /'
else
    warn "Java 8 not installed - the build will fail"
fi

log "Python 2 (some AOSP scripts need it)"
if apt-get install -y -qq python2 2>&1 | tail -2; then :; fi
if command -v python2 >/dev/null 2>&1; then
    python2 --version 2>&1 | sed 's/^/  /'
    # Many old scripts simply call "python".
    [ -e /usr/bin/python ] || ln -s "$(command -v python2)" /usr/bin/python
    echo "  /usr/bin/python -> $(readlink -f /usr/bin/python)"
else
    warn "python2 missing - individual build steps could fail"
fi

log "repo"
mkdir -p /usr/local/bin
curl -fsSL https://storage.googleapis.com/git-repo-downloads/repo -o /usr/local/bin/repo \
    && chmod a+x /usr/local/bin/repo \
    && echo "  repo installed"

log "ccache"
# 50 GB is generous, but cuts follow-up builds from hours to minutes.
ccache -M 50G >/dev/null 2>&1
ccache -o compression=true >/dev/null 2>&1
ccache -s 2>/dev/null | head -4 | sed 's/^/  /'

log "Basic git configuration"
# repo needs an identity - an existing one is left untouched.
git config --global user.name  >/dev/null || git config --global user.name  "aosp-build"
git config --global user.email >/dev/null || git config --global user.email "aosp-build@localhost"
git config --global color.ui   false
echo "  set"

log "Result"
printf '  %-14s %s\n' "Cores"   "$(nproc)"
printf '  %-14s %s\n' "RAM"     "$(free -h | awk '/^Mem:/{print $2" total, "$7" available"}')"
printf '  %-14s %s\n' "Disk"    "$(df -h / | awk 'NR==2{print $4" free of "$2}')"
for t in git git-lfs repo javac python python3 ccache make; do
    if command -v "$t" >/dev/null 2>&1; then
        printf '  %-14s ok\n' "$t"
    else
        printf '  %-14s MISSING\n' "$t"
    fi
done
