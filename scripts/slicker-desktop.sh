#!/usr/bin/env bash
# Builds (first run or --rebuild) and starts the slicker desktop at http://localhost:52342
# Requires: Node 20 on PATH, or npx (used to fetch Node 20 automatically).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
INSTALL_DIR="${SLICKER_HOME:-$HOME/.slicker}/server"
REBUILD=false
[[ "${1:-}" == "--rebuild" ]] && REBUILD=true

# Flipper's dependencies break on Node >= 22 (SlowBuffer removed). Use Node 20.
if [[ "$(node -v 2>/dev/null)" != v20.* ]]; then
  echo "Using Node 20 via npx..."
  NODE20_BIN="$(dirname "$(npx -y -p node@20 which node)")"
  export PATH="$NODE20_BIN:$PATH"
fi
echo "Node $(node -v)"

YARN=(npx -y yarn@1.22.22)

if $REBUILD || [[ ! -f "$INSTALL_DIR/server.js" ]]; then
  echo "Building flipper-server (takes a few minutes)..."
  (cd "$ROOT/desktop" && "${YARN[@]}" install --ignore-engines && "${YARN[@]}" build:flipper-server)
  # The release build stages a runnable server (deps already linked) in a temp folder.
  BUILD_DIR="$(ls -dt "${TMPDIR:-/tmp}"/flipper-build--* 2>/dev/null | head -1)"
  [[ -f "$BUILD_DIR/server.js" ]] || { echo "Build folder not found" >&2; exit 1; }
  echo "Installing server to $INSTALL_DIR..."
  rm -rf "$INSTALL_DIR"
  mkdir -p "$(dirname "$INSTALL_DIR")"
  cp -R "$BUILD_DIR" "$INSTALL_DIR"
fi

echo "Starting slicker desktop on http://localhost:52342"
cd "$INSTALL_DIR"
exec node server.js --open
