#!/usr/bin/env bash
#
# Fetches what ON-FireAI needs for its wake word, which this repository does not hold:
# - sherpa-onnx's Android library (Apache-2.0, onnxruntime MIT, linked in), arm64 only,
#   into onfireai/jni/arm64-v8a/
# - the keyword spotting model "zipformer gigaspeech 3.3M" (Apache-2.0), the small int8
#   files, into onfireai/assets/kws/ (the "mobile" variant does not run with this
#   sherpa-onnx: its encoder expects other chunk sizes)
# Both are checked against their SHA-256. Without them ON-FireAI builds and works, only
# without a wake word.
#
#   scripts/fetch-onfireai-kws.sh
#
set -euo pipefail
here=$(cd "$(dirname "$0")/.." && pwd)
app="$here/onfireai"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

LIB_URL=https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-v2.0.1-android-static-link-onnxruntime.tar.bz2
LIB_SHA=fe1c9ceffd86770218a1d4bba6b410d6f726b2b8dda6a5488499b05250b53aea
KWS_URL=https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2
KWS_SHA=f170013b4716e41b62b9bfd809687c207cef798ef9bc6534d524e17af9b6561a

fetch() { # url sha file
    curl -fsSL -o "$3" "$1"
    echo "$2  $3" | sha256sum -c - >/dev/null || { echo "checksum mismatch: $1" >&2; exit 1; }
}

fetch "$LIB_URL" "$LIB_SHA" "$tmp/lib.tar.bz2"
fetch "$KWS_URL" "$KWS_SHA" "$tmp/kws.tar.bz2"

mkdir -p "$app/jni/arm64-v8a" "$app/assets/kws"
tar -xjf "$tmp/lib.tar.bz2" -C "$tmp" ./jniLibs/arm64-v8a/libsherpa-onnx-jni.so
cp "$tmp/jniLibs/arm64-v8a/libsherpa-onnx-jni.so" "$app/jni/arm64-v8a/"

tar -xjf "$tmp/kws.tar.bz2" -C "$tmp"
m="$tmp/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
cp "$m/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx" "$app/assets/kws/encoder.onnx"
cp "$m/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx" "$app/assets/kws/decoder.onnx"
cp "$m/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx" "$app/assets/kws/joiner.onnx"
cp "$m/tokens.txt" "$app/assets/kws/tokens.txt"
echo "wake word files in $app/jni and $app/assets/kws"
