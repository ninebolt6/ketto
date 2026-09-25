#!/usr/bin/env python3
"""Aggregate counters and missed-branch inventory from a Kover XML report."""
import argparse
import sys
import xml.etree.ElementTree as ET

COUNTER_ORDER = ["BRANCH", "INSTRUCTION", "LINE", "METHOD", "CLASS"]


def fmt(covered: int, missed: int) -> str:
    total = covered + missed
    return f"{covered}/{total} = {100 * covered / total:.1f}%" if total else f"{covered}/{total}"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("report", nargs="?", default="build/reports/kover/report.xml")
    ap.add_argument("--methods", action="store_true", help="list methods carrying missed branches")
    ap.add_argument("--kind", default="BRANCH", help="counter type to detail (default BRANCH)")
    args = ap.parse_args()

    try:
        root = ET.parse(args.report).getroot()
    except (OSError, ET.ParseError) as e:
        sys.exit(f"cannot read {args.report}: {e}")

    agg = {c.get("type"): (int(c.get("covered")), int(c.get("missed"))) for c in root.findall("counter")}
    print("== aggregate ==")
    for ty in COUNTER_ORDER:
        if ty in agg:
            print(f"{ty}: {fmt(*agg[ty])}")
    for ty, (c, m) in sorted(agg.items()):
        if ty not in COUNTER_ORDER:
            print(f"{ty}: {fmt(c, m)}")

    print(f"== classes with missed {args.kind} ==")
    for cls in root.iter("class"):
        name = cls.get("name", "?").split("/")[-1]
        for counter in cls.findall("counter"):
            if counter.get("type") == args.kind and int(counter.get("missed")) > 0:
                print(f"  {name}: {counter.get('missed')} missed")

    if args.methods:
        print(f"== methods with missed {args.kind} ==")
        for cls in root.iter("class"):
            cname = cls.get("name", "?").split("/")[-1]
            for m in cls.iter("method"):
                for counter in m.findall("counter"):
                    if counter.get("type") == args.kind and int(counter.get("missed")) > 0:
                        print(f"  {cname}.{m.get('name')}: {counter.get('missed')} missed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
