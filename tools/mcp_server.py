#!/usr/bin/env python3
"""An MCP server that gives the agent the three facts it cannot otherwise get right.

Why a custom tool at all
------------------------
An agent with a shell can already run ``mvn test`` and ``cat`` a spec. What it cannot do
reliably is answer "which acceptance criteria does this change actually satisfy" — that
needs the spec and the test annotations cross-referenced, and asking a model to do that
by reading files is asking it to be a parser. It will be right most of the time, and
"most of the time" is the failure mode this whole workflow exists to remove.

So the rule for what belongs in here: a tool earns its place when it turns a judgement
call into a fact. ``spec_coverage`` does. ``run_tests`` does, because raw Maven output is
thousands of lines of which about four matter, and pushing the other thousands through
the context window is both slow and a good way to lose the important ones.

Registration (stdio transport):

    claude mcp add realm-ledger -- python3 tools/mcp_server.py

Protocol: JSON-RPC 2.0 over stdin/stdout, one message per line. Standard library only,
deliberately — a tool the team has to ``pip install`` before it works is a tool that
stops being used.
"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path
from typing import Any, Callable

REPO_ROOT = Path(__file__).resolve().parent.parent
SPEC_DIR = REPO_ROOT / "specs"
GATE = REPO_ROOT / "tools" / "spec_gate.py"
SELFCHECK = REPO_ROOT / "tools" / "core-selfcheck" / "SelfCheck.java"

PROTOCOL_VERSION = "2024-11-05"
SERVER_INFO = {"name": "realm-ledger", "version": "0.1.0"}

# Maven is chatty and almost none of it is signal. Keep the lines that decide whether the
# run passed and the ones that name a failure.
MAVEN_SIGNAL = ("Tests run:", "[ERROR]", "BUILD SUCCESS", "BUILD FAILURE", "FAIL")


# --------------------------------------------------------------------------- helpers


def run(command: list[str], timeout: int = 900) -> tuple[int, str]:
    try:
        completed = subprocess.run(
            command,
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
        )
    except subprocess.TimeoutExpired:
        return 124, f"timed out after {timeout}s: {' '.join(command)}"
    except FileNotFoundError as missing:
        return 127, f"command not found: {missing.filename}"
    return completed.returncode, (completed.stdout or "") + (completed.stderr or "")


def find_spec(spec_id: str) -> Path | None:
    for path in sorted(SPEC_DIR.glob("*.md")):
        if path.name == "TEMPLATE.md":
            continue
        if path.stem == spec_id or path.stem.startswith(f"{spec_id}-"):
            return path
    return None


# ----------------------------------------------------------------------------- tools


def tool_spec_list(_: dict) -> str:
    lines = []
    for path in sorted(SPEC_DIR.glob("*.md")):
        if path.name == "TEMPLATE.md":
            continue
        text = path.read_text(encoding="utf-8")
        status = "unknown"
        title = path.stem
        for line in text.splitlines()[:10]:
            if line.startswith("status:"):
                status = line.split(":", 1)[1].strip()
            if line.startswith("title:"):
                title = line.split(":", 1)[1].strip()
        criteria = sum(1 for line in text.splitlines() if line.startswith("### "))
        lines.append(f"{path.stem}  [{status}]  {title}  ({criteria} criteria)")
    return "\n".join(lines) if lines else "no specs found"


def tool_spec_read(args: dict) -> str:
    spec_id = str(args.get("spec_id", "")).strip()
    path = find_spec(spec_id)
    if path is None:
        return f"no spec matching {spec_id!r}. Try spec_list."
    return path.read_text(encoding="utf-8")


def tool_spec_coverage(args: dict) -> str:
    command = [sys.executable, str(GATE), "--json"]
    spec_id = args.get("spec_id")
    if spec_id:
        command += ["--spec", str(spec_id)]
    _, output = run(command, timeout=60)
    try:
        report = json.loads(output)
    except json.JSONDecodeError:
        return f"spec gate produced no parsable report:\n{output.strip()}"

    lines: list[str] = []
    for spec in report["specs"]:
        lines.append(f"{spec['spec']} [{spec['status']}]")
        for criterion in spec["criteria"]:
            mark = "COVERED " if criterion["covered"] else "UNCOVERED"
            lines.append(f"  {mark} {criterion['id']}  {criterion['title']}")
            for test in criterion["tests"]:
                lines.append(f"            {test}")
    for ref in report["dangling_refs"]:
        lines.append(f"DANGLING {ref['id']} at {ref['at']}")
    lines.append("")
    lines.append("GATE PASSES" if report["ok"] else "GATE FAILS")
    return "\n".join(lines)


def tool_core_check(_: dict) -> str:
    """Compile and run the dependency-free core check. Seconds, not minutes."""
    out = REPO_ROOT / "target" / "selfcheck"
    out.mkdir(parents=True, exist_ok=True)
    sources = [
        str(p)
        for package in ("core", "port", "application")
        for p in (REPO_ROOT / "src" / "main" / "java" / "io" / "realmledger" / package).rglob(
            "*.java"
        )
    ]
    code, compile_output = run(
        ["javac", "-d", str(out), "-Xlint:all", *sources, str(SELFCHECK)], timeout=180
    )
    if code != 0:
        return f"core check did not compile:\n{compile_output.strip()}"
    code, run_output = run(["java", "-cp", str(out), "SelfCheck"], timeout=120)
    verdict = "PASS" if code == 0 else "FAIL"
    return f"{run_output.strip()}\n\ncore check: {verdict}"


def tool_run_tests(args: dict) -> str:
    command = ["mvn", "-q", "-B", "test"]
    pattern = args.get("test_pattern")
    if pattern:
        command.append(f"-Dtest={pattern}")
    code, output = run(command)
    interesting = [
        line for line in output.splitlines() if any(token in line for token in MAVEN_SIGNAL)
    ]
    body = "\n".join(interesting[-60:]) or output.strip()[-4000:]
    return f"{body}\n\nexit={code} ({'PASS' if code == 0 else 'FAIL'})"


def tool_changed_files(args: dict) -> str:
    base = str(args.get("base", "HEAD"))
    code, output = run(["git", "diff", "--name-only", base], timeout=60)
    if code != 0:
        return f"git diff failed:\n{output.strip()}"
    _, untracked = run(["git", "ls-files", "--others", "--exclude-standard"], timeout=60)
    changed = [line for line in output.splitlines() if line.strip()]
    new = [line for line in untracked.splitlines() if line.strip()]
    if not changed and not new:
        return f"no changes against {base}"
    parts = []
    if changed:
        parts.append("changed:\n" + "\n".join(f"  {f}" for f in changed))
    if new:
        parts.append("untracked:\n" + "\n".join(f"  {f}" for f in new))
    return "\n\n".join(parts)


TOOLS: dict[str, tuple[str, dict, Callable[[dict], str]]] = {
    "spec_list": (
        "List every spec with its status and criterion count. Start here.",
        {"type": "object", "properties": {}},
        tool_spec_list,
    ),
    "spec_read": (
        "Return one spec verbatim. Use this rather than guessing what a criterion says.",
        {
            "type": "object",
            "properties": {
                "spec_id": {"type": "string", "description": "Spec id, for example 002."}
            },
            "required": ["spec_id"],
        },
        tool_spec_read,
    ),
    "spec_coverage": (
        "Cross-reference acceptance criteria against @SpecRef annotations in the tests. "
        "Reports which criteria have no test and which tests cite a criterion that does "
        "not exist. This is the authority on whether a spec is done.",
        {
            "type": "object",
            "properties": {
                "spec_id": {
                    "type": "string",
                    "description": "Limit to one spec. Omit to gate every implemented spec.",
                }
            },
        },
        tool_spec_coverage,
    ),
    "core_check": (
        "Compile and run the dependency-free domain self-check. Takes seconds and needs "
        "no Maven, Docker or network. Run this after every edit to core/, port/ or "
        "application/ before reaching for the full suite.",
        {"type": "object", "properties": {}},
        tool_core_check,
    ),
    "run_tests": (
        "Run the Maven test suite and return only the lines that matter. Slow: prefer "
        "core_check while iterating, and run this once before declaring a spec done.",
        {
            "type": "object",
            "properties": {
                "test_pattern": {
                    "type": "string",
                    "description": "Optional -Dtest value, for example WalletServiceTest.",
                }
            },
        },
        tool_run_tests,
    ),
    "changed_files": (
        "List files changed against a base ref, plus untracked files. Use this to scope a "
        "review to the actual diff instead of reading the whole repository.",
        {
            "type": "object",
            "properties": {
                "base": {"type": "string", "description": "Base ref. Defaults to HEAD."}
            },
        },
        tool_changed_files,
    ),
}


# -------------------------------------------------------------------------- protocol


def handle(message: dict) -> dict | None:
    method = message.get("method")
    request_id = message.get("id")

    if method == "initialize":
        return ok(
            request_id,
            {
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {"tools": {}},
                "serverInfo": SERVER_INFO,
            },
        )

    if method in ("notifications/initialized", "initialized"):
        return None  # notification: no reply

    if method == "ping":
        return ok(request_id, {})

    if method == "tools/list":
        return ok(
            request_id,
            {
                "tools": [
                    {"name": name, "description": description, "inputSchema": schema}
                    for name, (description, schema, _) in TOOLS.items()
                ]
            },
        )

    if method == "tools/call":
        params = message.get("params") or {}
        name = params.get("name")
        entry = TOOLS.get(name)
        if entry is None:
            return error(request_id, -32602, f"unknown tool: {name}")
        try:
            text = entry[2](params.get("arguments") or {})
            return ok(request_id, {"content": [{"type": "text", "text": text}]})
        except Exception as failure:  # surface as a tool error, never kill the server
            return ok(
                request_id,
                {
                    "content": [{"type": "text", "text": f"{type(failure).__name__}: {failure}"}],
                    "isError": True,
                },
            )

    if request_id is None:
        return None
    return error(request_id, -32601, f"method not found: {method}")


def ok(request_id: Any, result: dict) -> dict:
    return {"jsonrpc": "2.0", "id": request_id, "result": result}


def error(request_id: Any, code: int, message: str) -> dict:
    return {"jsonrpc": "2.0", "id": request_id, "error": {"code": code, "message": message}}


def main() -> int:
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            message = json.loads(line)
        except json.JSONDecodeError:
            continue
        response = handle(message)
        if response is not None:
            sys.stdout.write(json.dumps(response) + "\n")
            sys.stdout.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())
