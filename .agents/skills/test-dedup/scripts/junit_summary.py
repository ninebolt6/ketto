#!/usr/bin/env python3
"""JUnit XML totals plus skipped/failed test listing; skipped tests contribute zero coverage."""
import argparse
import glob
import os
import sys
import xml.etree.ElementTree as ET


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dir", default="build/test-results/test", help="JUnit XML results directory")
    args = ap.parse_args()

    files = sorted(glob.glob(os.path.join(args.dir, "*.xml")))
    if not files:
        sys.exit(f"no test results under {args.dir} — run the tests first")

    tests = failures = errors = skipped = 0
    dormant = []
    failed = []
    for path in files:
        try:
            suite = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        tests += int(suite.get("tests", 0))
        failures += int(suite.get("failures", 0))
        errors += int(suite.get("errors", 0))
        skipped += int(suite.get("skipped", 0))
        for tc in suite.iter("testcase"):
            label = f"{tc.get('classname', '?')}.{tc.get('name', '?')}"
            if tc.find("skipped") is not None:
                dormant.append(label)
            if tc.find("failure") is not None or tc.find("error") is not None:
                failed.append(label)

    print(f"tests={tests} failures={failures} errors={errors} skipped={skipped} across {len(files)} suites")
    if dormant:
        print("== skipped/dormant tests ==")
        for label in dormant:
            print(f"  {label}")
    if failed:
        print("== failed/errored tests ==")
        for label in failed:
            print(f"  {label}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
