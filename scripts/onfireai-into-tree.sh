#!/usr/bin/env bash
#
# Places ON-FireAI, the assistant (onfireai/ in this repository), into a LineageOS 16 tree
# as packages/apps/ONFireAI. The device's makefile adds it to the ROM when it is there.
#
#   scripts/onfireai-into-tree.sh <los16-root>
#
set -e
if [ $# -ne 1 ] || [ ! -d "$1/packages/apps" ]; then
    echo "usage: $0 <los16-root>" >&2
    exit 2
fi
here=$(cd "$(dirname "$0")/.." && pwd)
mkdir -p "$1/packages/apps/ONFireAI"
rsync -a --delete "$here/onfireai/" "$1/packages/apps/ONFireAI/"
echo "ON-FireAI -> $1/packages/apps/ONFireAI"
