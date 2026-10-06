#!/usr/bin/env bash
# Offline behavior gates: isolated homes, no provider credentials or alerts.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
host=$(cd "${KMET_SOURCE_ROOT:-$root/../../kmet}" && pwd)
mode=${1:-bb}
bb_bin=$(command -v "${BB_BIN:-bb}")
work=$(mktemp -d "${TMPDIR:-/tmp}/kmet-migration-check.XXXXXX")
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/home" "$work/agent" "$work/claude/projects" "$work/tmp"
# No real HOME, auth/config, HTTP hooks, provider env keys or project writes.
clean=(env -i "PATH=$PATH" "HOME=$work/home" "LANG=C.UTF-8" "TMPDIR=$work/tmp"
       "KMET_CODING_AGENT_DIR=$work/agent" "CLAUDE_CONFIG_DIR=$work/claude"
       "KMET_SOURCE_ROOT=$host" "SESSION_MIGRATE_ROOT=$root")
for name in NIX_SSL_CERT_FILE SSL_CERT_FILE SSL_CERT_DIR GIT_SSL_CAINFO; do
    [[ -z ${!name:-} ]] || clean+=("$name=${!name}")
done
printf 'kmet source: %s\n' "$(git -C "$host" rev-parse HEAD)"
if [[ $mode == bb ]]; then
    (cd "$root" && "${clean[@]}" "$bb_bin" test)
    (cd "$host" && "${clean[@]}" "$bb_bin" -e \
        '(require (quote babashka.classpath)) (babashka.classpath/add-classpath (str (System/getenv "SESSION_MIGRATE_ROOT") "/test")) (require (quote session-migrate.smoke)) (session-migrate.smoke/-main)')
elif [[ $mode == jolt ]]; then
    jolt_bin=$(command -v "${JOLT_BIN:-jolt}")
    unit_paths=$(python3 -c 'import json,sys; print("{:paths ["+" ".join(json.dumps(p) for p in sys.argv[1:])+"]}")' "$host/src" "$host/tasks" "$root/src" "$root/test")
    smoke_paths=$(python3 -c 'import json,sys; print("{:paths ["+" ".join(json.dumps(p) for p in sys.argv[1:])+"]}")' "$host/src" "$host/tasks" "$root/test")
    (cd "$host" && "${clean[@]}" "$jolt_bin" -Sdeps "$unit_paths" -m session-migrate.test-runner)
    (cd "$host" && "${clean[@]}" "$jolt_bin" -Sdeps "$smoke_paths" -m session-migrate.smoke)
else
    echo 'Usage: scripts/check.sh [bb|jolt]' >&2
    exit 2
fi
