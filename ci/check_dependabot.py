#!/usr/bin/env python3
"""Guard the Dependabot configuration.

The file this replaces looked reasonable but was not valid YAML: its update
entries were indented one level short, so GitHub rejected the whole file and
Dependabot never ran. Nothing caught it, because nothing read the file.

This checks that the configuration parses, that both ecosystems this repo
needs are present, and that the dependencies which are build targets (the
Paper API and VaultAPI) stay out of automatic version updates.
"""

import io
import os
import sys

import yaml

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONFIG = os.path.join(ROOT, ".github", "dependabot.yml")

REQUIRED_ECOSYSTEMS = {"gradle", "github-actions"}
# Build targets, not dependencies: each version module compiles against the
# Paper API for the Minecraft line it ships, and Vault's API is pinned at 1.7.
IGNORED = {"io.papermc.paper:paper-api", "com.github.MilkBowl:VaultAPI"}
INTERVALS = {"daily", "weekly", "monthly"}


def fail(message):
    print("FAIL: " + message)
    return 1


def main():
    try:
        with io.open(CONFIG, encoding="utf-8") as handle:
            data = yaml.safe_load(handle)
    except FileNotFoundError:
        return fail(".github/dependabot.yml is missing")
    except yaml.YAMLError as error:
        return fail(".github/dependabot.yml is not valid YAML: %s" % error)

    if not isinstance(data, dict) or data.get("version") != 2:
        return fail("dependabot.yml must declare version: 2")

    updates = data.get("updates")
    if not isinstance(updates, list) or not updates:
        return fail("dependabot.yml has no updates entries")

    ecosystems = {}
    for entry in updates:
        if not isinstance(entry, dict):
            return fail("every updates entry must be a mapping")
        ecosystem = entry.get("package-ecosystem")
        if ecosystem in ecosystems:
            return fail("duplicate package-ecosystem: %s" % ecosystem)
        ecosystems[ecosystem] = entry
        interval = (entry.get("schedule") or {}).get("interval")
        if interval not in INTERVALS:
            return fail("%s has no valid schedule.interval" % ecosystem)
        if not isinstance(entry.get("open-pull-requests-limit"), int):
            return fail("%s must bound open-pull-requests-limit" % ecosystem)

    missing = REQUIRED_ECOSYSTEMS - set(ecosystems)
    if missing:
        return fail("missing package-ecosystem: %s" % ", ".join(sorted(missing)))

    gradle = ecosystems["gradle"]
    ignored = {
        item.get("dependency-name")
        for item in gradle.get("ignore", [])
        if isinstance(item, dict)
    }
    missing_ignores = IGNORED - ignored
    if missing_ignores:
        return fail(
            "these build targets must not be bumped automatically: %s"
            % ", ".join(sorted(missing_ignores))
        )

    # A grouped update is one pull request, which is what keeps the live-server
    # matrix from running once per dependency.
    if not gradle.get("groups"):
        return fail("gradle updates must be grouped to bound CI runs")

    print("dependabot.yml ok: %s" % ", ".join(sorted(ecosystems)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
