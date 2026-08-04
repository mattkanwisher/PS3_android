#!/usr/bin/env sh
# Apply patches/series to the rpcs3/ submodule. Idempotence: run on a clean
# submodule only (git -C rpcs3 checkout -- . && git -C rpcs3 clean -fd to reset).
set -eu
cd "$(dirname "$0")/.."

applied=0
while IFS= read -r p; do
    case "$p" in ''|\#*) continue ;; esac
    echo "Applying patches/$p"
    git -C rpcs3 apply --verbose "../patches/$p"
    applied=$((applied + 1))
done < patches/series

echo "Applied $applied patch(es)."
