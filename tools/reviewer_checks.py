#!/usr/bin/env python3
"""Dependency-free static assertions for the claims made to Plugin Hub reviewers."""
from __future__ import annotations

import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA_ROOT = ROOT / "src/main/java/com/hcimguide"
FAILURES: list[str] = []


def fail(message: str) -> None:
    FAILURES.append(message)


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def int_constant(text: str, name: str) -> int | None:
    m = re.search(rf"\b{name}\s*=\s*([0-9_]+)(?:\s*\*\s*([0-9_]+))?(?:\s*\*\s*([0-9_]+))?", text)
    if not m:
        return None
    value = 1
    for group in m.groups():
        if group is not None:
            value *= int(group.replace("_", ""))
    return value


java_files = sorted(JAVA_ROOT.glob("*.java"))
all_java = "\n".join(read(path) for path in java_files)
plugin_text = read(JAVA_ROOT / "HcimGuidePlugin.java")
config_text = read(JAVA_ROOT / "HcimGuideConfig.java")

# Plugin Hub/Jagex-sensitive mechanisms.
for label, pattern in {
    "generated input": r"java\.awt\.Robot|new\s+(?:MouseEvent|KeyEvent)\s*\(|\.dispatchEvent\s*\(",
    "generated game actions": r"invokeMenuAction|client\.menuAction\s*\(|ClientPacket|PacketBuffer|queueClickPacket|\.interact\s*\(",
    "menu mutation": r"setMenuEntries\s*\(|createMenuEntry\s*\(",
    "subprocess execution": r"ProcessBuilder|Runtime\.getRuntime\s*\(\)\.exec|\.exec\s*\(",
    "native loading/JNI": r"System\.load(?:Library)?\s*\(|\bnative\s+\w+\s*\(",
    "reflection imports or dynamic reflection": r"java\.lang\.reflect|com\.google\.gson\.reflect|Class\.forName\s*\(|setAccessible\s*\(|getDeclared(?:Method|Field|Constructor)\s*\(|MethodHandles|Proxy\.newProxyInstance",
    "direct sockets": r"java\.net\.(?:Socket|DatagramSocket|ServerSocket)|SocketChannel",
    "runtime font-file loading": r"Font\.createFont|registerFont\s*\(",
    "plugin-owned thread/factory": r"new\s+Thread\s*\(|Executors\.|new\s+ForkJoinPool\s*\(",
    "dynamic source/class loading": r"URLClassLoader|JavaCompiler|ToolProvider\.getSystemJavaCompiler|ScriptEngineManager",
}.items():
    if re.search(pattern, all_java):
        fail(f"prohibited or review-sensitive mechanism found: {label}")

# Network is deliberately owned by exactly two classes and is read-only.
network_owners: list[Path] = []
for path in java_files:
    if "okhttp3" in read(path):
        network_owners.append(path)
expected_network = [JAVA_ROOT / "GuideService.java", JAVA_ROOT / "LocationDbDownloader.java"]
if network_owners != expected_network:
    fail("network ownership changed: " + ", ".join(p.name for p in network_owners))

network_text = "\n".join(read(path) for path in expected_network)
if re.search(r"RequestBody|FormBody|MultipartBody|\.post\s*\(|\.put\s*\(|\.patch\s*\(", network_text):
    fail("network owner contains an upload/request-body API")
urls = sorted(set(re.findall(r'"(https?://[^"\s]+)"', network_text)))
if not urls:
    fail("no runtime URL literals found in network owners; host allowlist check is vacuous")
if any(not url.startswith("https://") for url in urls):
    fail("non-HTTPS runtime URL literal found")
allowed_hosts = {"oldschool.runescape.wiki", "raw.githubusercontent.com"}
for url in urls:
    host = url.split("/", 3)[2]
    if host not in allowed_hosts:
        fail(f"unexpected runtime host: {host}")
if re.search(r"raw\.githubusercontent\.com/[^\s\"]+/(?:main|master)/", network_text):
    fail("moving-branch raw GitHub URL found")
for forbidden_type in ("Client", "Player", "ItemContainer", "Skill", "Quest", "WorldPoint"):
    if re.search(rf"import\s+net\.runelite\.api\.{forbidden_type}\s*;", network_text):
        fail(f"network owner imports gameplay type {forbidden_type}")
if "Authorization" in network_text or "Cookie" in network_text:
    fail("network owner references authentication/session headers")

# Normal logs must not copy stable identifiers or user-imported guide content.
sensitive_log_tokens = re.compile(
    r"getAccountHash|getRSProfileKey|currentGuideId|guideId|stepKey|getText\s*\(|targetName|customOverlayFontFamily",
    re.IGNORECASE,
)
for path in java_files:
    text = read(path)
    for match in re.finditer(r"log\.(?:info|warn|error)\s*\((.*?)\);", text, re.DOTALL):
        # Strip format literals, then allow only boolean profile-key nullness.
        body = re.sub(r'"(?:\\.|[^"\\])*"', '""', match.group(1))
        body = re.sub(r"\b(?:managerKey|profileKey)\s*!=\s*null", "boolean", body)
        if sensitive_log_tokens.search(body) or re.search(r"\b(?:managerKey|profileKey)\b", body):
            line = text.count("\n", 0, match.start()) + 1
            fail(f"sensitive value in normal log: {path.name}:{line}")

# Account/profile PII use is deliberately narrow.
account_uses = [m.start() for m in re.finditer(r"getAccountHash\s*\(\)", plugin_text)]
if len(account_uses) != 1:
    fail(f"expected one account-hash readiness use, found {len(account_uses)}")
else:
    context = plugin_text[account_uses[0] : account_uses[0] + 100]
    if "== -1L" not in context:
        fail("account hash is used for more than readiness")
if re.search(r"getLocalPlayer\s*\(\)\s*\.\s*getName\s*\(", all_java):
    fail("local player name is read")
for pii_api in ("getHostName", "getHostAddress", "NetworkInterface", "getHardwareAddress", "user.name", "user.home"):
    if pii_api in all_java:
        # RuneLite.RUNELITE_DIR is acceptable; direct JVM identity/home probes are not.
        fail(f"host/user identity API found: {pii_api}")

# Resources must work from a Plugin Hub JAR.
if re.search(r"\.getResource\s*\(", all_java):
    fail("Class.getResource is used; prefer getResourceAsStream for Plugin Hub JARs")

# Overlay ownership must be symmetrical.
added = set(re.findall(r"overlayManager\.add\((\w+)\)", plugin_text))
removed = set(re.findall(r"overlayManager\.remove\((\w+)\)", plugin_text))
expected_overlays = {"targetOverlay", "hudOverlay", "directionArrowOverlay", "stepNavOverlay", "dialogOptionOverlay"}
if added != expected_overlays or removed != expected_overlays:
    fail(f"overlay lifecycle mismatch: add={sorted(added)}, remove={sorted(removed)}")

# Lifecycle-owned caches/callbacks have explicit reset points. The
# client-thread portion lives in resetClientThreadState(), which shutDown
# queues (and startUp re-queues for the race where a re-enable outruns the
# queued cleanup) - so the reset calls are checked across both bodies, and
# the shutDown -> reset linkage is asserted explicitly.
shutdown_match = re.search(r"protected void shutDown\s*\(\)\s*\{(.*?)\n\t\}", plugin_text, re.DOTALL)
reset_match = re.search(r"private void resetClientThreadState\s*\(\)\s*\{(.*?)\n\t\}", plugin_text, re.DOTALL)
if not shutdown_match or not reset_match:
    fail("could not locate plugin shutDown/resetClientThreadState")
else:
    if "resetClientThreadState" not in shutdown_match.group(1):
        fail("shutDown does not queue resetClientThreadState")
    shutdown = shutdown_match.group(1) + reset_match.group(1)
    for call in (
        "PanelFonts.clear()",
        "OverlayFonts.clear()",
        "iconResolver.reset()",
        "hudOverlay.resetCaches()",
        "ItemGridPanel.clearInventoryBackground()",
        "guideService.cancelInFlight()",
        "locationDbDownloader.cancelInFlight()",
        "inputHandler.unregister()",
        "tickFailuresLogged.clear()",
        "activePhaseIds.clear()",
        "activeWaypointIndexes.clear()",
        "objectNameCache.clear()",
        "pathfinder.clear()",
    ):
        if call not in shutdown:
            fail(f"shutdown cleanup missing: {call}")

# Cache and client-thread work bounds.
resolver = read(JAVA_ROOT / "ItemIconResolver.java")
chunk = int_constant(resolver, "SCAN_CHUNK")
tracked = int_constant(resolver, "MAX_TRACKED_NAMES")
if chunk is None or chunk > 1000:
    fail("item-definition scan chunk is missing or above 1000")
if tracked is None or tracked > 100_000:
    fail("item-name cache bound is missing or above 100,000")
if "scanGeneration" not in resolver or "scanStateLock" not in resolver:
    fail("item-definition scan lacks generation cancellation or synchronized ownership")
overlay_fonts = read(JAVA_ROOT / "OverlayFonts.java")
overlay_cap = int_constant(overlay_fonts, "MAX_CACHED_FONTS")
if overlay_cap is None or overlay_cap > 256 or "void clear()" not in overlay_fonts:
    fail("OverlayFonts.java lacks an acceptable enforced bound/reset")
# PanelFonts must be bounded either by its own capped cache or by fully
# delegating resolution AND reset to the bounded OverlayFonts cache.
panel_fonts = read(JAVA_ROOT / "PanelFonts.java")
panel_cap = int_constant(panel_fonts, "MAX_CACHED_FONTS")
own_bound = panel_cap is not None and panel_cap <= 128 and "void clear()" in panel_fonts
delegated = ("OverlayFonts.resolve(" in panel_fonts
             and "OverlayFonts.clear()" in panel_fonts
             and "new ConcurrentHashMap" not in panel_fonts
             and "new HashMap" not in panel_fonts
             and "new LinkedHashMap" not in panel_fonts)
if not (own_bound or delegated):
    fail("PanelFonts.java lacks an acceptable enforced bound/reset")

# Native hint arrow is opt-in and every set call is locally gated.
native_default = re.search(r"default boolean nativeHintArrow\s*\(\)\s*\{\s*return\s+false\s*;", config_text, re.DOTALL)
if not native_default:
    fail("native hint arrow is not opt-in/default false")
for match in re.finditer(r"client\.setHintArrow\s*\(", plugin_text):
    context = plugin_text[max(0, match.start() - 350) : match.start()]
    if "config.nativeHintArrow()" not in context:
        line = plugin_text.count("\n", 0, match.start()) + 1
        fail(f"setHintArrow not visibly gated by nativeHintArrow: HcimGuidePlugin.java:{line}")

# Persisted/imported data has concrete pre-allocation caps.
required_caps = {
    "HcimGuidePlugin.java": ("MAX_STORED_STATE_CHARS", "MAX_STORED_STATE_ENTRIES", "MAX_STORED_STATE_KEY_CHARS"),
    "GuideRegistry.java": ("MAX_REGISTRY_CHARS", "MAX_USER_GUIDES"),
    "GuideService.java": ("MAX_GUIDE_BYTES",),
    "ProgressCodec.java": ("MAX_DECODED_BYTES", "MAX_ENCODED_CHARS", "MAX_KEYS", "MAX_KEY_CHARS"),
    "CustomLocationStore.java": ("MAX_JSON_CHARS", "MAX_READ_CHARS", "MAX_GUIDES", "MAX_STEPS", "MAX_WAYPOINTS"),
    "NpcLocationStore.java": ("MAX_IMPORT_ENTRIES", "MAX_IMPORT_CHARS", "MAX_STORE_BYTES"),
    "LocationDbDownloader.java": ("MAX_BODY_BYTES", "MAX_ENTRIES"),
    "WikitextParser.java": ("MAX_STEPS", "MAX_EPISODES", "MAX_BANKS_PER_EPISODE", "MAX_STEP_SOURCE_CHARS"),
    "JsonGuideParser.java": ("MAX_STEPS", "MAX_EPISODES"),
}
for filename, names in required_caps.items():
    text = read(JAVA_ROOT / filename)
    for name in names:
        if int_constant(text, name) is None:
            fail(f"input/storage bound missing: {filename}:{name}")

# Standard build, Java only, and no extra runtime dependency review burden.
properties = read(ROOT / "runelite-plugin.properties")
if not re.search(r"(?m)^build=standard$", properties):
    fail("runelite-plugin.properties is not build=standard")
if list((ROOT / "src/main").rglob("*.kt")) or list((ROOT / "src/main").rglob("*.scala")):
    fail("non-Java JVM source found")
build = read(ROOT / "build.gradle")
for dep in re.findall(r"(?m)^\s*(?:implementation|api|runtimeOnly)\s+(.+)$", build):
    fail(f"runtime dependency requires extra review: {dep.strip()}")
if "JavaVersion.VERSION_11" not in build:
    fail("Java 11 source/target configuration missing")

# Reviewer automation, Plugin Hub metadata and repository hygiene.
for required in (
    ROOT / "README.md",
    ROOT / "LICENSE",
    ROOT / "CONTRIBUTING.md",
    ROOT / "PLUGIN_HUB_REVIEW.md",
    ROOT / "runelite-plugin.properties",
    ROOT / "icon.png",
    ROOT / "tools/audit.sh",
    ROOT / "tools/audit-capabilities.sh",
    ROOT / "tools/check_transport_data.py",
    ROOT / "tools/pre-submit.sh",
):
    if not required.exists():
        fail(f"review/submission file missing: {required.relative_to(ROOT)}")
if "BSD 2-Clause License" not in read(ROOT / "LICENSE"):
    fail("root LICENSE is not the documented BSD 2-Clause license")
metadata = {}
for line in properties.splitlines():
    if "=" in line:
        key, value = line.split("=", 1)
        metadata[key.strip()] = value.strip()
for key in ("displayName", "author", "description", "tags", "plugins", "build"):
    if not metadata.get(key):
        fail(f"runelite-plugin.properties field missing/empty: {key}")
if metadata.get("plugins") != "com.hcimguide.HcimGuidePlugin":
    fail("unexpected plugin main class in runelite-plugin.properties")

# Root icon must meet the Plugin Hub dimensions without relying on Pillow.
icon = ROOT / "icon.png"
try:
    raw = icon.read_bytes()
    if len(raw) < 24 or raw[:8] != b"\x89PNG\r\n\x1a\n" or raw[12:16] != b"IHDR":
        fail("root icon.png is not a valid PNG header")
    else:
        width = int.from_bytes(raw[16:20], "big")
        height = int.from_bytes(raw[20:24], "big")
        if width <= 0 or height <= 0 or width > 48 or height > 72:
            fail(f"root icon.png dimensions exceed Plugin Hub limits: {width}x{height}")
except OSError:
    fail("root icon.png is missing or unreadable")

unwanted_patterns = ("__pycache__", "*.pyc", "*.class", "*.dll", "*.so", "*.dylib", "*.exe")
unwanted_files = []
for pattern in unwanted_patterns:
    unwanted_files.extend(ROOT.rglob(pattern))
for unwanted in unwanted_files:
    # Ignore Gradle output if a developer ran the gate locally; packaging excludes build.
    # A TOP-LEVEL "harness/" directory is the offline dev harness some developers
    # keep beside the source - never committed or shipped, but full of compiled
    # .class files when in use. Only that exact top-level directory is exempt.
    parts = unwanted.relative_to(ROOT).parts
    if "build" not in parts and ".gradle" not in parts and parts[0] != "harness":
        fail(f"generated/native artifact present in source tree: {unwanted.relative_to(ROOT)}")

# Wrapper checksum property is explicit; wrapper JAR remains the only bundled JAR.
wrapper_props = read(ROOT / "gradle/wrapper/gradle-wrapper.properties")
if "distributionSha256Sum=" not in wrapper_props:
    fail("Gradle distributionSha256Sum is missing")
jars = sorted(p for p in ROOT.rglob("*.jar") if p.relative_to(ROOT).parts[0] != "harness")
expected_jar = ROOT / "gradle/wrapper/gradle-wrapper.jar"
if jars != [expected_jar]:
    fail("unexpected bundled JAR(s): " + ", ".join(str(p.relative_to(ROOT)) for p in jars))
try:
    with zipfile.ZipFile(expected_jar) as zf:
        if not zf.namelist():
            fail("Gradle wrapper JAR is empty")
except (OSError, zipfile.BadZipFile):
    fail("Gradle wrapper JAR is unreadable")

if FAILURES:
    print("REVIEWER CHECKS FAILED")
    for item in FAILURES:
        print(f"- {item}")
    sys.exit(1)

print("REVIEWER CHECKS PASS")
print(f"- Java runtime files: {len(java_files)}")
print("- Network owners: GuideService.java, LocationDbDownloader.java")
print("- Runtime hosts: " + ", ".join(sorted(allowed_hosts)))
print("- Requests: GET only; no request bodies/auth headers")
print("- Normal logs: no account/profile keys or imported step/font text")
print(f"- Overlays: {len(expected_overlays)} added/removed symmetrically")
print(f"- Item scan: {chunk} IDs/chunk; {tracked:,}-name hard cap; generation-cancelled")
print("- Font caches: bounded and cleared")
print("- Native hint arrow: opt-in and gated")
print("- Persisted/imported inputs: explicit size/count/key caps")
print("- Lifecycle cache/callback cleanup: present")
print("- Metadata/license/icon: complete; root icon within Plugin Hub dimensions")
print("- Build: Java 11 standard build; no extra runtime dependencies")
