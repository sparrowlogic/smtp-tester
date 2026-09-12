#!/usr/bin/env bash
#
# Stop hook: refuses to let a turn end while `make agent-completion` fails.
#
# The gate is written down in AGENTS.md, but a written rule is advisory. This makes it structural:
# the turn does not end until the build is green, and the failure output is fed straight back so
# the next step is to fix it rather than to rediscover it.
#
# Reads the hook payload on stdin and emits a hook JSON decision on stdout.

set -uo pipefail

payload=$(cat)

# Claude Code sets stop_hook_active when it is re-entering Stop because a previous Stop hook
# blocked. Without this check, a persistently failing build would block forever instead of handing
# control back to the user.
if printf '%s' "$payload" | grep -q '"stop_hook_active"[[:space:]]*:[[:space:]]*true'; then
  exit 0
fi

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
log=$(mktemp -t agent-completion)
trap 'rm -f "$log"' EXIT

if make -C "$repo_root" agent-completion >"$log" 2>&1; then
  exit 0
fi

# Only the tail is fed back: a Maven failure puts the cause in the last few dozen lines, and the
# full log would be thousands of lines of dependency resolution.
reason=$(printf 'make agent-completion FAILED -- the work is not finished.\n\n%s' \
  "$(tail -n 60 "$log")")

python3 - "$reason" <<'PY'
import json, sys
print(json.dumps({"decision": "block", "reason": sys.argv[1]}))
PY
