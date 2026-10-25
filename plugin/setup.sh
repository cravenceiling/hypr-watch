#!/usr/bin/env bash
set -euo pipefail
plugin_root="$(cd "$(dirname "$0")" && pwd)"
python3 -m pip install --target "$plugin_root/server/vendor" -r "$plugin_root/requirements.txt"
