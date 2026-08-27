#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

if [[ -z $(git status --porcelain) ]]; then
    echo "Nothing to commit."
    exit 0
fi

git add -A
git commit -m "${1:-auto commit}"
git push
