#!/usr/bin/env bash
# Builds dist/Slicker-<version>-arm64.dmg (Apple Silicon). Needs Xcode command line tools.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [[ "$(node -v 2>/dev/null)" != v20.* ]]; then
  echo "Using Node 20 via npx..."
  export PATH="$(dirname "$(npx -y -p node@20 which node)"):$PATH"
fi
cd "$ROOT/desktop"
npx -y yarn@1.22.22 install --ignore-engines
npx -y yarn@1.22.22 build:flipper-server --mac-local --dmg "$@"
ls -lh "$ROOT"/dist/Slicker-*.dmg
