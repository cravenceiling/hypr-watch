#!/usr/bin/env bash
set -euo pipefail
plugin_root="$(cd "$(dirname "$0")" && pwd)"
cd "$plugin_root"
npm ci
npm run build
npm link
