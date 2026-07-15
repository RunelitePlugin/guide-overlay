#!/usr/bin/env bash
# Guide Overlay - one-shot Plugin Hub submission script.
#
# Run this from the plugin folder (the one containing build.gradle):
#   Windows: right-click the folder -> "Open Git Bash here" -> ./tools/submit.sh
#   Mac/Linux: ./tools/submit.sh
#
# It will, in order: pin the location-database source to a commit hash,
# create the guide-overlay repository on YOUR GitHub account, push the code,
# fork runelite/plugin-hub, add the two-line manifest, and open the pull
# request - printing what it's about to do before every network call.
#
# You need: git (you have it), and a GitHub Personal Access Token:
#   GitHub -> Settings -> Developer settings -> Personal access tokens ->
#   Tokens (classic) -> Generate new token -> scope: "repo" only.
# REVOKE the token from the same page as soon as this script finishes.
set -euo pipefail

api() { # method path [json-body]
	local method="$1" path="$2" body="${3:-}"
	if [ -n "$body" ]; then
		curl -sS -X "$method" -H "Authorization: token $TOKEN" \
			-H "Accept: application/vnd.github+json" \
			-d "$body" "https://api.github.com$path"
	else
		curl -sS -X "$method" -H "Authorization: token $TOKEN" \
			-H "Accept: application/vnd.github+json" \
			"https://api.github.com$path"
	fi
}

echo "== Guide Overlay -> RuneLite Plugin Hub submission =="
[ -f build.gradle ] && [ -d .git ] || { echo "ERROR: run this from the guide-overlay folder (must contain build.gradle and .git)"; exit 1; }

read -rp "Your pseudonymous GitHub username: " GH_USER
read -rsp "Personal access token (input hidden; revoke it afterwards): " TOKEN; echo

echo "-> Verifying the token works (GET /user)..."
LOGIN=$(api GET /user | sed -n 's/.*"login": *"\([^"]*\)".*/\1/p' | head -1)
[ "$LOGIN" = "$GH_USER" ] || { echo "ERROR: token belongs to '$LOGIN', not '$GH_USER'"; exit 1; }

echo "-> Ensuring anonymous commit identity for this repo..."
git config user.name "guide-overlay"
git config user.email "guide-overlay@users.noreply.github.com"

echo "-> Pinning the NPC location dataset to a commit hash (reproducible builds)..."
DATA_SHA=$(git ls-remote https://github.com/mejrs/data_osrs master | cut -f1)
[ -n "$DATA_SHA" ] || { echo "ERROR: could not resolve mejrs/data_osrs"; exit 1; }
DL=src/main/java/com/hcimguide/LocationDbDownloader.java
if grep -q "data_osrs/master/" "$DL"; then
	sed -i.bak "s|data_osrs/master/|data_osrs/$DATA_SHA/|" "$DL" && rm -f "$DL.bak"
	git add "$DL"
	git commit -m "Pin location database source to commit $DATA_SHA"
	echo "   pinned to $DATA_SHA"
else
	echo "   already pinned - skipping"
fi

echo "-> Pinning the BRUHsailer guide source to a commit hash (reproducible builds)..."
BRUH_SHA=$(git ls-remote https://github.com/umkyzn/BRUHsailer HEAD | cut -f1)
[ -n "$BRUH_SHA" ] || { echo "ERROR: could not resolve umkyzn/BRUHsailer"; exit 1; }
GR=src/main/java/com/hcimguide/GuideRegistry.java
if grep -q "BRUHsailer/main/" "$GR"; then
	sed -i.bak "s|BRUHsailer/main/|BRUHsailer/$BRUH_SHA/|" "$GR" && rm -f "$GR.bak"
	git add "$GR"
	git commit -m "Pin BRUHsailer guide source to commit $BRUH_SHA"
	echo "   pinned to $BRUH_SHA"
else
	echo "   already pinned - skipping"
fi

echo "-> Creating repository $GH_USER/guide-overlay (public)..."
api POST /user/repos '{"name":"guide-overlay","description":"RuneLite plugin: wiki guides as an in-client checklist with auto-completion and target highlighting","has_wiki":false}' >/dev/null || true

echo "-> Pushing the code..."
BRANCH=$(git rev-parse --abbrev-ref HEAD)
git remote remove origin 2>/dev/null || true
git remote add origin "https://$GH_USER:$TOKEN@github.com/$GH_USER/guide-overlay.git"
git push -u origin "$BRANCH"
git remote set-url origin "https://github.com/$GH_USER/guide-overlay.git"  # token removed from git config
PLUGIN_SHA=$(git rev-parse HEAD)
echo "   pushed commit $PLUGIN_SHA"

echo "-> Forking runelite/plugin-hub..."
api POST /repos/runelite/plugin-hub/forks >/dev/null
echo "   waiting 20s for the fork to be ready..."
sleep 20

HUB_BRANCH=$(api GET /repos/runelite/plugin-hub | sed -n 's/.*"default_branch": *"\([^"]*\)".*/\1/p' | head -1)
[ -n "$HUB_BRANCH" ] || HUB_BRANCH=master

echo "-> Adding plugins/guide-overlay manifest to your fork..."
MANIFEST=$(printf 'repository=https://github.com/%s/guide-overlay.git\ncommit=%s\n' "$GH_USER" "$PLUGIN_SHA" | base64 | tr -d '\n')
api PUT "/repos/$GH_USER/plugin-hub/contents/plugins/guide-overlay" \
	"{\"message\":\"Add Guide Overlay\",\"content\":\"$MANIFEST\",\"branch\":\"$HUB_BRANCH\"}" >/dev/null

echo "-> Opening the pull request..."
PR_BODY="Adds Guide Overlay: wiki guides (B0aty HCIM Guide V3 and the BRUHsailer Ironman Guide built in, any OSRS-wiki guide addable by link) as an in-client checklist with verifiable-step auto-completion, NPC/ground-item highlighting, a far-target compass, and per-character progress.\n\nNetwork disclosure: three HTTPS endpoints, all strictly one-time and behind explicit user confirmation dialogs - the OSRS wiki API (guide text import), a commit-pinned umkyzn/BRUHsailer file (the built-in BRUHsailer guide's JSON), and a commit-pinned mejrs/data_osrs file (optional NPC location dataset, the data behind the wiki's world map). Zero automatic network traffic; everything is cached locally. No automation of any kind - display and tracking only."
PR_URL=$(api POST /repos/runelite/plugin-hub/pulls \
	"{\"title\":\"Add Guide Overlay\",\"head\":\"$GH_USER:$HUB_BRANCH\",\"base\":\"$HUB_BRANCH\",\"body\":\"$PR_BODY\"}" \
	| sed -n 's/.*"html_url": *"\(https:[^"]*\/pull\/[0-9]*\)".*/\1/p' | head -1)

echo
echo "== DONE =="
echo "Pull request: ${PR_URL:-open https://github.com/runelite/plugin-hub/pulls and check your PR}"
echo
echo "NOW: revoke the access token (GitHub -> Settings -> Developer settings -> Tokens)."
echo "Watch the PR from your pseudonymous account for reviewer feedback."
