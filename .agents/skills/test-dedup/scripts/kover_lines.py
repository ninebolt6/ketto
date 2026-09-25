#!/usr/bin/env python3
"""List partial (pc) / uncovered (nc) source lines in a Kover HTML report; the XML report has no line granularity."""
import argparse
import html
import re
import sys
from pathlib import Path

TITLE_RE = re.compile(r"<title>([^<]*)</title>")
MARKER_RE = re.compile(r'<b class="(fc|pc|nc)">')


def class_name(text: str, path: Path) -> str:
    m = TITLE_RE.search(text)
    if not m:
        return path.stem
    return html.unescape(m.group(1)).rsplit(">", 1)[-1].strip() or path.stem


def uncovered_lines(text: str):
    src = text.find('id="sourceCode"')
    if src < 0:
        return
    body = text[src + len('id="sourceCode"') + 1:]
    for lineno, segment in enumerate(body.split("\n"), start=1):
        m = MARKER_RE.match(segment)
        if not m or m.group(1) == "fc":
            continue
        code = html.unescape(segment[m.end():].replace("</b>", "")).replace("\xa0", " ").strip()
        yield lineno, m.group(1), code


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--html", default="build/reports/kover/html", help="koverHtmlReport output directory")
    ap.add_argument("--class", dest="cls", metavar="NAME", help="filter to classes whose name contains NAME")
    ap.add_argument("--kind", choices=["pc", "nc"], help="only partial or only uncovered lines")
    args = ap.parse_args()

    html_dir = Path(args.html)
    if not html_dir.is_dir():
        sys.exit(f"{html_dir} not found — run the koverHtmlReport Gradle task first")

    found = False
    for path in sorted(html_dir.rglob("*.html")):
        text = path.read_text(encoding="utf-8", errors="replace")
        name = class_name(text, path)
        if args.cls and args.cls.lower() not in name.lower():
            continue
        lines = [(ln, k, c) for ln, k, c in uncovered_lines(text) if not args.kind or k == args.kind]
        if not lines:
            continue
        found = True
        print(f"== {name} ==")
        for ln, kind, code in lines:
            print(f"  {kind} L{ln}: {code}")
    if not found:
        print("no partial/uncovered lines" + (f" for --class {args.cls}" if args.cls else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
