#!/usr/bin/env python3
"""Fail the build when an implemented spec has a criterion no test claims.

Why this exists
---------------
"The tests pass" and "the spec is satisfied" are different statements. An agent
optimises for the first one, because that is the signal it can see. Left alone it will
write three tests for the criterion it found easiest and quietly skip the one about
concurrent retries, and the diff will look complete.

This gate makes the gap machine-visible. Every acceptance criterion in a spec marked
``status: implemented`` must be claimed by at least one ``@SpecRef("ID")`` in the test
sources, and every ``@SpecRef`` must point at a criterion that actually exists. Both
directions matter: the first catches the missing test, the second catches a test that
cites a criterion someone renamed or deleted.

It is intentionally dumb. It does not check that the test is any good; a reviewer still
does that. It only removes the failure mode where nobody notices a criterion was never
attempted.

Usage
-----
    python3 tools/spec_gate.py                 # gate every implemented spec
    python3 tools/spec_gate.py --spec 002      # one spec, including drafts
    python3 tools/spec_gate.py --json          # machine-readable, for the MCP server
    python3 tools/spec_gate.py --include-drafts

Exit status is 0 when the gate passes and 1 when it does not.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SPEC_DIR = REPO_ROOT / "specs"
TEST_DIR = REPO_ROOT / "src" / "test" / "java"

# "### WD-4 — Repeating the same request returns the original result"
CRITERION_HEADING = re.compile(r"^###\s+([A-Z]{2,5}-\d+)\s*(?:[—–-]\s*)?(.*)$")
SPEC_REF = re.compile(r'@SpecRef\(\s*"([A-Z]{2,5}-\d+)"\s*\)')
# Nearest following method declaration, so a failure names something greppable.
METHOD_NAME = re.compile(r"^\s*(?:public\s+|private\s+|protected\s+)?void\s+(\w+)\s*\(")


@dataclass
class Criterion:
    id: str
    title: str
    line: int


@dataclass
class Spec:
    path: Path
    spec_id: str
    title: str
    status: str
    criteria: list[Criterion] = field(default_factory=list)

    @property
    def is_implemented(self) -> bool:
        return self.status == "implemented"


@dataclass
class Reference:
    criterion_id: str
    file: str
    line: int
    method: str


def parse_front_matter(text: str) -> dict[str, str]:
    if not text.startswith("---"):
        return {}
    end = text.find("\n---", 3)
    if end == -1:
        return {}
    fields: dict[str, str] = {}
    for line in text[3:end].splitlines():
        if ":" in line:
            key, _, value = line.partition(":")
            fields[key.strip()] = value.strip()
    return fields


def load_specs(spec_dir: Path) -> list[Spec]:
    specs: list[Spec] = []
    for path in sorted(spec_dir.glob("*.md")):
        if path.name == "TEMPLATE.md":
            continue
        text = path.read_text(encoding="utf-8")
        meta = parse_front_matter(text)
        spec = Spec(
            path=path,
            spec_id=meta.get("id", path.stem.split("-", 1)[0]),
            title=meta.get("title", path.stem),
            status=meta.get("status", "draft"),
        )
        for number, line in enumerate(text.splitlines(), start=1):
            match = CRITERION_HEADING.match(line)
            if match:
                spec.criteria.append(
                    Criterion(id=match.group(1), title=match.group(2).strip(), line=number)
                )
        specs.append(spec)
    return specs


def load_references(test_dir: Path) -> list[Reference]:
    references: list[Reference] = []
    if not test_dir.exists():
        return references
    for path in sorted(test_dir.rglob("*.java")):
        lines = path.read_text(encoding="utf-8").splitlines()
        for index, line in enumerate(lines):
            for match in SPEC_REF.finditer(line):
                references.append(
                    Reference(
                        criterion_id=match.group(1),
                        file=str(path.relative_to(REPO_ROOT)),
                        line=index + 1,
                        method=find_method(lines, index),
                    )
                )
    return references


def find_method(lines: list[str], start: int) -> str:
    """The first method declaration at or below the annotation, within 10 lines."""
    for line in lines[start : start + 10]:
        match = METHOD_NAME.match(line)
        if match:
            return match.group(1)
    return "<unknown>"


def build_report(
    all_specs: list[Spec],
    gated_specs: list[Spec],
    references: list[Reference],
    include_drafts: bool,
) -> dict:
    by_criterion: dict[str, list[Reference]] = {}
    for reference in references:
        by_criterion.setdefault(reference.criterion_id, []).append(reference)

    # Criterion ids are resolved against EVERY spec, not just the gated subset.
    # Scoping this to the filtered list made `--spec 002` report every WD-* reference as
    # dangling, which is exactly the kind of false alarm that trains people to ignore a
    # gate. See docs/RUNS.md, run 3.
    known_ids = {c.id for spec in all_specs for c in spec.criteria}
    gated = [s for s in gated_specs if s.is_implemented or include_drafts]

    spec_reports = []
    uncovered_total = 0
    for spec in gated:
        criteria = []
        for criterion in spec.criteria:
            refs = by_criterion.get(criterion.id, [])
            covered = bool(refs)
            if not covered and (spec.is_implemented or include_drafts):
                uncovered_total += 1
            criteria.append(
                {
                    "id": criterion.id,
                    "title": criterion.title,
                    "covered": covered,
                    "tests": [f"{r.file}:{r.line} {r.method}" for r in refs],
                }
            )
        spec_reports.append(
            {
                "spec": spec.path.name,
                "id": spec.spec_id,
                "title": spec.title,
                "status": spec.status,
                "criteria": criteria,
                "uncovered": [c["id"] for c in criteria if not c["covered"]],
            }
        )

    dangling = [
        {"id": r.criterion_id, "at": f"{r.file}:{r.line}", "method": r.method}
        for r in references
        if r.criterion_id not in known_ids
    ]

    return {
        "specs": spec_reports,
        "dangling_refs": dangling,
        "skipped_drafts": [
            s.path.name for s in gated_specs if not s.is_implemented and not include_drafts
        ],
        "ok": uncovered_total == 0 and not dangling,
        "uncovered_count": uncovered_total,
    }


def print_human(report: dict) -> None:
    for spec in report["specs"]:
        header = f"{spec['spec']}  [{spec['status']}]"
        print(f"\n{header}\n{'-' * len(header)}")
        for criterion in spec["criteria"]:
            mark = "ok  " if criterion["covered"] else "MISS"
            print(f"  {mark} {criterion['id']}  {criterion['title']}")
            for test in criterion["tests"]:
                print(f"         {test}")

    if report["skipped_drafts"]:
        print(f"\nskipped (draft): {', '.join(report['skipped_drafts'])}")

    if report["dangling_refs"]:
        print("\ndangling @SpecRef annotations (criterion does not exist):")
        for ref in report["dangling_refs"]:
            print(f"  {ref['id']} at {ref['at']} in {ref['method']}")

    print()
    if report["ok"]:
        print("spec gate OK")
    else:
        missing = report["uncovered_count"]
        print(
            f"spec gate FAILED: {missing} criterion(s) with no test, "
            f"{len(report['dangling_refs'])} dangling reference(s)"
        )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--spec", help="Only this spec id, for example 002. Implies drafts.")
    parser.add_argument("--json", action="store_true", help="Emit JSON instead of text.")
    parser.add_argument(
        "--include-drafts",
        action="store_true",
        help="Gate draft specs too. Off by default: a draft is not supposed to pass yet.",
    )
    args = parser.parse_args()

    all_specs = load_specs(SPEC_DIR)
    gated_specs = all_specs
    if args.spec:
        gated_specs = [
            s for s in all_specs if s.spec_id == args.spec or s.path.stem.startswith(args.spec)
        ]
        if not gated_specs:
            print(f"no spec matching {args.spec!r} in {SPEC_DIR}", file=sys.stderr)
            return 2

    references = load_references(TEST_DIR)
    report = build_report(
        all_specs,
        gated_specs,
        references,
        include_drafts=args.include_drafts or bool(args.spec),
    )

    if args.json:
        print(json.dumps(report, indent=2))
    else:
        print_human(report)

    return 0 if report["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
