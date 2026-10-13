#!/usr/bin/env python3
"""Print store automation gate status. Never mutates stores."""
from __future__ import annotations

import argparse
import os

GATES = {
    "ios": [
        "IOS_RELEASE_AUTOMATION",
        "IOS_UPLOAD_BUILD",
        "IOS_UPLOAD_LISTING",
        "IOS_UPLOAD_WHATS_NEW",
        "IOS_UPLOAD_SCREENSHOTS",
        "IOS_SUBMIT_REVIEW",
        "IOS_PUBLIC_RELEASE",
    ],
    "android": [
        "ANDROID_RELEASE_AUTOMATION",
        "ANDROID_UPLOAD_LISTING",
        "ANDROID_UPLOAD_WHATS_NEW",
        "ANDROID_UPLOAD_SCREENSHOTS",
        "ANDROID_INTERNAL_TESTING",
        "ANDROID_CLOSED_TESTING",
        "ANDROID_PRODUCTION_ROLLOUT",
    ],
    "shared": ["STORE_SYNC_REVENUECAT"],
}


def enabled(name: str) -> bool:
    return os.environ.get(name, "").strip().lower() in {"1", "true", "yes", "on"}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--platform", choices=("ios", "android", "all"), default="all")
    args = parser.parse_args()
    groups = ["ios", "android"] if args.platform == "all" else [args.platform]
    groups.append("shared")

    print("Store automation gates (false = setup-only / safe):")
    any_on = False
    for group in groups:
        print(f"  [{group}]")
        for name in GATES[group]:
            on = enabled(name)
            any_on = any_on or on
            print(f"    {name}={'true' if on else 'false'}")
    if "ios" in groups and not enabled("IOS_RELEASE_AUTOMATION"):
        print("  note: IOS_RELEASE_AUTOMATION is off, so every iOS store operation is skipped")
    if "android" in groups and not enabled("ANDROID_RELEASE_AUTOMATION"):
        print("  note: ANDROID_RELEASE_AUTOMATION is off, so android-v* tags build and release nothing")
    if not any_on:
        print("all gates off — no live upload/listing/submit/rollout/sync will run")
    else:
        print("WARNING: one or more live gates are on")


if __name__ == "__main__":
    main()
