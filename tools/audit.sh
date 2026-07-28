#!/usr/bin/env bash
# Lightweight, dependency-free safety audit for Guide Overlay.
set -euo pipefail
cd "$(dirname "$0")/.."

fail=0
hit_file=$(mktemp)
trap 'rm -f "$hit_file"' EXIT HUP INT TERM
check_absent() {
  local label="$1" pattern="$2"
  if grep -RInE --include='*.java' "$pattern" src/main/java > "$hit_file" 2>/dev/null; then
    echo "FAIL: $label"
    cat "$hit_file"
    fail=1
  else
    echo "PASS: $label"
  fi
}

check_absent "no generated mouse/keyboard input" 'java\.awt\.Robot|MouseEvent\(|KeyEvent\(|setMouse|setKeyboard'
# match actual dispatch mechanisms, not the MenuAction enum type itself -
# READING MenuAction values (e.g. classifying the player's own clicks) is
# fine and expected; only invoking/synthesizing actions is prohibited
check_absent "no generated in-game actions" 'invokeMenuAction|client\.menuAction\(|\.interact\(|ClientPacket|PacketBuffer|queueClickPacket'
check_absent "no subprocess execution" 'ProcessBuilder|Runtime\.getRuntime\(\)\.exec|\.exec\('
check_absent "no native loading/JNI" 'System\.load(Library)?\(|native[[:space:]]+[A-Za-z_][A-Za-z0-9_]*[[:space:]]*\('
check_absent "no dangerous reflection" 'setAccessible\(|Class\.forName\(|getDeclared(Method|Field|Constructor)\('
check_absent "no direct socket/protocol access" 'java\.net\.(Socket|DatagramSocket)|SocketChannel|sendPacket|writePacket'
check_absent "no credential literals" '(ghp_|github_pat_|AKIA)[A-Za-z0-9_]+'

if bash -n tools/*.sh; then
  echo "PASS: repository shell syntax"
else
  echo "FAIL: repository shell syntax"
  fail=1
fi

if grep -RInE 'raw\.githubusercontent\.com/.+/(main|master)/' src/main/java; then
  echo "WARN: moving-branch raw URLs remain. Pin them to commit SHAs before Plugin Hub submission."
else
  echo "PASS: raw GitHub data URLs are commit-pinned"
fi

printf '\nURL literals in network-owner source files (including comments):\n'
grep -hoE 'https://[^"[:space:]]+' \
  src/main/java/com/hcimguide/GuideService.java \
  src/main/java/com/hcimguide/LocationDbDownloader.java | sort -u || true

if [ "$fail" -ne 0 ]; then
  exit 1
fi

echo "AUDIT PASS (with any warnings shown above)"
