#!/usr/bin/env python3
"""Generate the Kotlin category registry from registry.tsv — M6.

Why a generated source file rather than a runtime resource: :core is a Kotlin
Multiplatform commonMain module, and resource loading differs per platform
(classpath on JVM, assets on Android). A generated Kotlin map is identical
everywhere, costs nothing at startup, and cannot fail to load on one platform.

The TSV stays the source of truth because it is what a human reviews. The
locked rule is a "top-500 human-reviewed registry", and a diff of a TSV is
reviewable in a way that a diff of a Kotlin map is not.

Usage:  python3 tools/registry-builder/build-registry.py
"""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
TSV = ROOT / "tools/registry-builder/registry.tsv"
OUT = ROOT / "core/src/commonMain/kotlin/dev/lumen/core/category/GeneratedRegistry.kt"

VALID = {
    "Communication", "Development", "Reading", "Writing",
    "Browsing", "Media", "Games", "Utilities",
}


def main() -> int:
    entries: dict[str, str] = {}
    problems: list[str] = []

    for lineno, raw in enumerate(TSV.read_text().splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = raw.split("\t")
        if len(parts) != 2:
            problems.append(f"{lineno}: expected <key>\\t<category>, got {raw!r}")
            continue
        key, category = parts[0].strip(), parts[1].strip()
        if not key:
            problems.append(f"{lineno}: empty key")
        elif category not in VALID:
            # A typo here would silently drop an app into Uncategorized, which
            # looks like the registry simply not covering it.
            problems.append(f"{lineno}: unknown category {category!r}")
        elif key.lower() in entries:
            # Lookup is case-insensitive, so two keys differing only in case
            # would be one entry with an arbitrary winner.
            problems.append(f"{lineno}: duplicate key {key!r}")
        else:
            entries[key.lower()] = category

    if problems:
        print("registry.tsv has problems:", file=sys.stderr)
        for p in problems:
            print("  " + p, file=sys.stderr)
        return 1

    rows = "\n".join(
        f'        "{k}" to Category.{v},' for k, v in sorted(entries.items())
    )
    OUT.write_text(
        f'''package dev.lumen.core.category

import dev.lumen.core.model.AppKey

/**
 * GENERATED — do not edit.
 *
 * Source: `tools/registry-builder/registry.tsv`
 * Regenerate: `python3 tools/registry-builder/build-registry.py`
 *
 * The TSV is the human-reviewed artefact (`docs/plan.md`: "top-500
 * human-reviewed registry"); this file is its compiled form. A generated
 * Kotlin map rather than a runtime resource because :core is commonMain and
 * resource loading differs per platform — this is identical everywhere and
 * cannot fail to load on one of them.
 *
 * Entries: {len(entries)}
 */
object GeneratedRegistry : CategoryRegistry {{

    override fun lookup(appKey: AppKey): Category? =
        BY_KEY[appKey.value.trim().lowercase()]

    /** Every key in the registry, for the corpus test and tooling. */
    val keys: Set<String> get() = BY_KEY.keys

    private val BY_KEY: Map<String, Category> = mapOf(
{rows}
    )
}}
''',
    )
    print(f"wrote {OUT.relative_to(ROOT)} ({len(entries)} entries)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
