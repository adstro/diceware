#!/usr/bin/env python3
"""Fails when a static-analysis bypass is not in config/analysis-exceptions.txt, or an approved one is gone.

A bypass is anything that stops an analyzer from reporting: an inline suppression, a rule switched off or
narrowed in an analyzer's config, or a path the analyzer skips. Each approved bypass is one registry line,
`path | id | count | reason`, and is added only after /interrogate and the project owner approve it.

Usage: scripts/analysis-exceptions.py          check the tree against the registry (CI)
       scripts/analysis-exceptions.py --list   print the bypasses found, in registry form
"""
import re
import subprocess
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REGISTRY = ROOT / "config/analysis-exceptions.txt"

# (pattern, kind, lives in a comment): code and XML attribute suppressions are skipped inside comments,
# where they are only being mentioned; SwiftLint and codespell directives are always comments.
INLINE = [
    (re.compile(r"@(?:file:)?Suppress(?:Warnings|Lint)?\(([^)]*)\)"), "suppress", False),
    (re.compile(r'tools:ignore="([^"]*)"'), "lint-ignore", False),
    (re.compile(r'tools:targetApi="([^"]*)"'), "lint-targetApi", False),
    (re.compile(r"swiftlint:disable(?::\w+)?\s+([\w ]+)"), "swiftlint-disable", True),
    (re.compile(r"codespell:ignore\b"), "codespell-ignore", True),
]
SCANNED = (".kt", ".kts", ".swift", ".xml")


def tracked_files_all():
    out = subprocess.run(["git", "ls-files", "--cached", "--others", "--exclude-standard"], cwd=ROOT, capture_output=True, text=True, check=True).stdout
    return out.splitlines()


def tracked_files():
    return [p for p in tracked_files_all() if p.endswith(SCANNED) and not p.endswith(".pbxproj")]


def inline_bypasses():
    found = Counter()
    for path in tracked_files():
        for line in (ROOT / path).read_text(encoding="utf-8").splitlines():
            in_comment = line.lstrip().startswith(("//", "<!--", "#", "*"))
            for pattern, kind, directive in INLINE:
                if in_comment and not directive:
                    continue
                for match in pattern.finditer(line):
                    rules = re.findall(r"[\w.:-]+", match.group(1)) if match.groups() else ["*"]
                    for rule in rules:
                        found[(path, f"{kind}:{rule}")] += 1
    return found


def detekt_config(path="config/detekt.yml"):
    found = Counter()
    section = rule = None
    for line in (ROOT / path).read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        indent = len(line) - len(line.lstrip())
        key, _, value = line.strip().partition(":")
        value = value.split("#")[0].strip()
        if indent == 0:
            section, rule = key, None
        elif indent == 2:
            rule = key
        elif indent == 4 and rule and (
            (key == "active" and value == "false") or key.startswith("ignore") or key in ("excludes", "allowedNames")
        ):
            found[(path, f"detekt:{section}.{rule}.{key}={value}")] += 1
    return found


def swiftlint_config(path="iosApp/.swiftlint.yml"):
    found = Counter()
    block = None
    for line in (ROOT / path).read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if not line.startswith(" "):
            block = line.split(":")[0]
            if block == "strict" and "true" not in line:
                found[(path, "swiftlint:strict=false")] += 1
        elif block in ("disabled_rules", "excluded") and line.strip().startswith("- "):
            found[(path, f"swiftlint:{block}:{line.strip()[2:]}")] += 1
        elif line.strip().startswith("ignores_") and "true" in line:
            found[(path, f"swiftlint:{block}.{line.strip().split(':')[0]}")] += 1
    return found


def android_lint_config(path="androidApp/build.gradle.kts"):
    found = Counter()
    text = (ROOT / path).read_text(encoding="utf-8")
    for match in re.finditer(r"disable\s*\+=\s*setOf\(([^)]*)\)", text):
        for rule in re.findall(r'"(\w+)"', match.group(1)):
            found[(path, f"android-lint:disable:{rule}")] += 1
    for setting in ("abortOnError = false", "ignoreWarnings = true", "baseline =", "lintConfig ="):
        if setting in text:
            found[(path, f"android-lint:{setting.split()[0]}")] += 1
    return found


def codespell_config(path=".codespellrc"):
    found = Counter()
    for line in (ROOT / path).read_text(encoding="utf-8").splitlines():
        key, _, value = line.partition("=")
        if key.strip() in ("skip", "ignore-words-list", "ignore-words", "ignore-regex"):
            for item in value.split(","):
                found[(path, f"codespell:{key.strip()}:{item.strip()}")] += 1
    return found


# Build settings that turn a whole analyzer or the warnings gate off.
GATES = re.compile(
    r"allWarningsAsErrors\s*=\s*false|warningsAsErrors\s*=\s*false|suppressWarnings\s*=\s*true|-nowarn|-Xsuppress-warning"
    r"|ignoreFailures\s*=\s*true|SWIFT_TREAT_WARNINGS_AS_ERRORS:\s*NO|SWIFT_SUPPRESS_WARNINGS:\s*YES|GCC_WARN_INHIBIT_ALL_WARNINGS:\s*YES"
)

# Deleting one of these turns a gate off as surely as setting it to false.
REQUIRED_GATES = [
    ("shared/build.gradle.kts", "allWarningsAsErrors = true"),
    ("androidApp/build.gradle.kts", "allWarningsAsErrors = true"),
    ("androidApp/build.gradle.kts", "warningsAsErrors = true"),
    ("iosApp/project.yml", "SWIFT_TREAT_WARNINGS_AS_ERRORS: YES"),
    ("iosApp/.swiftlint.yml", "strict: true"),
]


def build_gates():
    found = Counter()
    for path in [p for p in tracked_files_all() if p.endswith((".kts", "project.yml"))]:
        for match in GATES.finditer((ROOT / path).read_text(encoding="utf-8")):
            setting = re.sub(r"\s+", " ", match.group(0))
            found[(path, f"gate:{setting}")] += 1
    for path, setting in REQUIRED_GATES:
        if setting not in (ROOT / path).read_text(encoding="utf-8"):
            found[(path, f"gate-missing:{setting}")] += 1
    return found


def baselines():
    return Counter({(p, "baseline-file"): 1 for p in tracked_files_all() if "baseline" in Path(p).name.lower()})


def all_bypasses():
    return inline_bypasses() + detekt_config() + swiftlint_config() + android_lint_config() + codespell_config() + build_gates() + baselines()


def registry():
    approved = Counter()
    for number, line in enumerate(REGISTRY.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip() or line.startswith("#"):
            continue
        parts = [part.strip() for part in line.split("|")]
        if len(parts) != 4 or not parts[2].isdigit() or not parts[3]:
            sys.exit(f"{REGISTRY.name}:{number}: expected `path | id | count | reason`")
        approved[(parts[0], parts[1])] += int(parts[2])
    return approved


def main():
    found = all_bypasses()
    if "--list" in sys.argv:
        for (path, ident), count in sorted(found.items()):
            print(f"{path} | {ident} | {count} | ")
        return
    approved = registry()
    unapproved = {k: n - approved[k] for k, n in found.items() if n > approved[k]}
    stale = {k: n - found[k] for k, n in approved.items() if n > found[k]}
    for (path, ident), n in sorted(unapproved.items()):
        print(f"UNAPPROVED {path} | {ident} (+{n}): needs /interrogate and owner approval, then a registry line")
    for (path, ident), n in sorted(stale.items()):
        print(f"STALE {path} | {ident} (-{n}): no longer in the code; remove or lower it in the registry")
    if unapproved or stale:
        sys.exit(1)
    print(f"{sum(found.values())} analysis bypasses, all approved in {REGISTRY.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
