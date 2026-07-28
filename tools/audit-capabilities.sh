#!/usr/bin/env bash
# Asserts the capability boundaries the plugin claims. Fails loudly if a
# capability spreads to a file that should not have it, so an accidental
# regression is caught by running this rather than by reading 5,000 lines.
set -euo pipefail

ROOT="src/main/java/com/hcimguide"
fail=0

expect_owner() {
	local label="$1" pattern="$2" expected="$3"
	local actual
	actual="$(grep -RlE "$pattern" "$ROOT" 2>/dev/null | sort | tr '\n' ' ' | sed 's/ $//')"
	if [ "$actual" != "$expected" ]; then
		echo "FAIL  $label"
		echo "      expected: $expected"
		echo "      actual  : ${actual:-<none>}"
		fail=1
	else
		echo "ok    $label -> $expected"
	fi
}

echo "== capability owners =="
expect_owner "input API" \
	'net\.runelite\.client\.input|util\.HotkeyListener' \
	"$ROOT/GuideInputHandler.java"

expect_owner "clipboard + browser" \
	'java\.awt\.Toolkit|java\.awt\.datatransfer|net\.runelite\.client\.util\.LinkBrowser' \
	"$ROOT/GuideExternalActions.java"

expect_owner "audio output" \
	'net\.runelite\.client\.audio|javax\.sound' \
	"$ROOT/ChimePlayer.java"

expect_owner "network" \
	'okhttp3' \
	"$ROOT/GuideService.java $ROOT/LocationDbDownloader.java"

echo
echo "== prohibited mechanisms =="
if grep -RInE \
	'java\.awt\.Robot|Runtime\.getRuntime|ProcessBuilder|System\.load|System\.loadLibrary|Class\.forName|setAccessible|MethodHandles|Proxy\.newProxyInstance|\.dispatchEvent|new MouseEvent|new KeyEvent|invokeMenuAction|setMenuEntries' \
	"$ROOT"; then
	echo "FAIL  a prohibited mechanism is present above"
	fail=1
else
	echo "ok    none present"
fi

echo
if [ "$fail" -ne 0 ]; then
	echo "capability audit FAILED"
	exit 1
fi
echo "capability audit passed"
