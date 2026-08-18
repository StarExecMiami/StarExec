#!/usr/bin/env python3
"""
audit-dto-hydration.py — Static analysis script for StarExec DTO hydration gaps.

Compares the columns SELECTed by stored procedures in R__procedures_and_views.sql
against the columns read by Java DAO hydration methods (resultTo*, resultSetTo*).

Produces a report showing:
  1. Each stored procedure and the columns it actually returns.
  2. Each Java hydration callsite, which procedure it invokes, and which columns
     the hydration method attempts to read.
  3. MISMATCH warnings where the Java code reads a column that the procedure
     does not SELECT — i.e., DTO fields that will be left at Java defaults.

Usage:
  python3 scripts/audit-dto-hydration.py [--json] [--verbose]

Requires: Python 3.8+
No external dependencies.
"""

import argparse
import json
import os
import re
import sys
from collections import defaultdict
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Optional


# ─── Configuration ──────────────────────────────────────────────────────────

REPO_ROOT = Path(__file__).resolve().parent.parent
SQL_FILE = REPO_ROOT / "starexec-app" / "src" / "main" / "resources" / "db" / "migration" / "R__procedures_and_views.sql"
DAO_DIR = REPO_ROOT / "starexec-app" / "src" / "main" / "java" / "org" / "starexec" / "data" / "database"
SUPPRESSION_FILE = REPO_ROOT / "scripts" / ".dto-audit-suppress"


# ─── Data structures ───────────────────────────────────────────────────────

@dataclass
class ProcedureInfo:
    name: str
    line: int
    return_columns: list  # Column names from SELECT/RETURN QUERY
    raw_select: str = ""  # The raw SELECT statement for debugging


@dataclass
class HydrationSite:
    file: str
    line: int
    method_name: str       # e.g. "resultToPair", "resultSetToSolver"
    columns_read: list     # Column names the method tries to read from ResultSet
    procedure_called: str = ""  # Procedure name if we can trace it


@dataclass
class CallerInfo:
    file: str
    line: int
    java_method: str       # The calling Java method
    procedure_called: str  # SQL procedure invoked
    hydration_method: str  # Which resultTo* method processes the ResultSet


@dataclass
class Mismatch:
    hydration_method: str
    hydration_file: str
    column_read: str
    procedure: str
    procedure_line: int
    severity: str          # "ERROR" if required (throws), "WARNING" if optional


# ─── SQL Parsing ────────────────────────────────────────────────────────────

def parse_procedures(sql_path: Path) -> dict:
    """Parse stored procedures and extract their SELECT column lists."""
    procedures = {}

    if not sql_path.exists():
        print(f"ERROR: SQL file not found: {sql_path}", file=sys.stderr)
        return procedures

    text = sql_path.read_text(encoding="utf-8")
    lines = text.split("\n")

    # Pattern to match CREATE OR REPLACE FUNCTION/PROCEDURE
    create_re = re.compile(
        r"CREATE\s+OR\s+REPLACE\s+(?:FUNCTION|PROCEDURE)\s+"
        r"(?:starexec\.)?([\w]+)\s*\(",
        re.IGNORECASE,
    )

    # Find each procedure boundary
    proc_starts = []
    for i, line in enumerate(lines):
        m = create_re.search(line)
        if m:
            proc_starts.append((i, m.group(1)))

    for idx, (start_line, proc_name) in enumerate(proc_starts):
        # Determine end boundary (next procedure or EOF)
        if idx + 1 < len(proc_starts):
            end_line = proc_starts[idx + 1][0]
        else:
            end_line = len(lines)

        proc_body = "\n".join(lines[start_line:end_line])
        columns = extract_select_columns(proc_body)

        procedures[proc_name.lower()] = ProcedureInfo(
            name=proc_name,
            line=start_line + 1,  # 1-indexed
            return_columns=[c.lower() for c in columns],
            raw_select=_extract_primary_select(proc_body),
        )

    return procedures


def extract_select_columns(proc_body: str) -> list:
    """Extract column names/aliases from the SELECT clause of a procedure body."""
    columns = []

    # Look for RETURN QUERY SELECT ... FROM or just SELECT ... FROM
    # We want the final/main SELECT, typically after RETURN QUERY
    select_match = re.search(
        r"(?:RETURN\s+QUERY\s+)?SELECT\s+(.*?)\s+FROM\s",
        proc_body,
        re.IGNORECASE | re.DOTALL,
    )
    if not select_match:
        return columns

    select_clause = select_match.group(1)

    # Remove SQL comments
    select_clause = re.sub(r"--[^\n]*", "", select_clause)
    select_clause = re.sub(r"/\*.*?\*/", "", select_clause, flags=re.DOTALL)

    # Split by commas, handling nested parentheses
    parts = split_select_columns(select_clause)

    for part in parts:
        part = part.strip()
        if not part or part == "*":
            columns.append("*")
            continue

        col_name = extract_column_alias(part)
        if col_name:
            columns.append(col_name)

    return columns


def split_select_columns(clause: str) -> list:
    """Split a SELECT column list by commas, respecting parentheses."""
    parts = []
    depth = 0
    current = []
    for ch in clause:
        if ch == "(":
            depth += 1
            current.append(ch)
        elif ch == ")":
            depth -= 1
            current.append(ch)
        elif ch == "," and depth == 0:
            parts.append("".join(current))
            current = []
        else:
            current.append(ch)
    if current:
        parts.append("".join(current))
    return parts


def extract_column_alias(expr: str) -> str:
    """Given a SELECT expression, extract the effective column name or alias."""
    expr = expr.strip().rstrip(",")

    # Check for explicit AS alias
    as_match = re.search(r"\bAS\s+[\"']?(\w+)[\"']?\s*$", expr, re.IGNORECASE)
    if as_match:
        return as_match.group(1)

    # If it's table.column, take column
    dot_match = re.match(r"^[\w.]+\.(\w+)$", expr.strip())
    if dot_match:
        return dot_match.group(1)

    # If it's a simple identifier
    simple_match = re.match(r"^(\w+)$", expr.strip())
    if simple_match:
        return simple_match.group(1)

    # CASE expressions, function calls etc — try trailing word
    trailing = re.search(r"(\w+)\s*$", expr)
    if trailing:
        return trailing.group(1)

    return ""


def _extract_primary_select(proc_body: str) -> str:
    """Extract the primary SELECT statement for display."""
    m = re.search(
        r"((?:RETURN\s+QUERY\s+)?SELECT\s+.*?\s+FROM\s)",
        proc_body,
        re.IGNORECASE | re.DOTALL,
    )
    if m:
        text = m.group(1)
        # Truncate for readability
        if len(text) > 500:
            text = text[:500] + "..."
        return text.strip()
    return ""


# ─── Java Parsing ───────────────────────────────────────────────────────────

def parse_hydration_methods(dao_dir: Path) -> dict:
    """Parse Java DAO files and extract hydration method column reads."""
    methods = {}

    if not dao_dir.exists():
        print(f"ERROR: DAO directory not found: {dao_dir}", file=sys.stderr)
        return methods

    for java_file in sorted(dao_dir.glob("*.java")):
        text = java_file.read_text(encoding="utf-8")
        file_methods = extract_hydration_methods(text, java_file.name)
        methods.update(file_methods)

    return methods


def extract_hydration_methods(java_text: str, filename: str) -> dict:
    """Find resultTo*/resultSetTo* methods and extract columns they read."""
    methods = {}
    lines = java_text.split("\n")

    # Find method signatures
    method_re = re.compile(
        r"(?:public|protected|private|static)\s+.*?\s+"
        r"(result(?:Set)?To\w+|fromResultSet)\s*\(",
        re.IGNORECASE,
    )

    for i, line in enumerate(lines):
        m = method_re.search(line)
        if m:
            method_name = m.group(1)
            # Extract body until matching brace
            body = extract_method_body(lines, i)
            columns = extract_columns_from_java(body)

            key = f"{filename}::{method_name}"
            methods[key] = HydrationSite(
                file=filename,
                line=i + 1,
                method_name=method_name,
                columns_read=columns,
            )

    return methods


def extract_method_body(lines: list, start: int) -> str:
    """Extract method body from opening brace to matching close brace."""
    body_lines = []
    depth = 0
    found_open = False

    for i in range(start, min(start + 300, len(lines))):
        line = lines[i]
        body_lines.append(line)
        for ch in line:
            if ch == "{":
                depth += 1
                found_open = True
            elif ch == "}":
                depth -= 1
        if found_open and depth <= 0:
            break

    return "\n".join(body_lines)


def extract_columns_from_java(body: str) -> list:
    """Extract column name strings from ResultSet access patterns in Java code."""
    columns = []

    # We only want simple SQL column identifiers passed to ResultSet accessor methods.
    # A valid column name is alphanumeric + underscores + dots (for table.column aliases).
    valid_col_re = re.compile(r'^[a-zA-Z_][a-zA-Z0-9_.]*$')

    # Pattern 1: ResultSetUtils.getInt/getString/etc(results, "column_name", ...)
    # Pattern 2: cols.getInt(results, "column_name", ...)
    # Pattern 3: results.getInt("column_name")
    # Pattern 4: ResultSetUtils.getInt(results, prefix + "column_name", ...)

    # Direct string literal column references
    col_patterns = [
        # ResultSetUtils or cols method calls with string args — capture each quoted arg
        r'(?:ResultSetUtils|cols)\.(?:getInt|getString|getBoolean|getDouble|getLong|getTimestamp)\s*\([^;]*?"([^"]+)"',
        # results.getXxx("column")
        r'results?\.(?:getInt|getString|getBoolean|getDouble|getLong|getTimestamp)\s*\(\s*"([^"]+)"',
        # Catch prefix + "column" patterns (for prefixed hydration)
        r'prefix\s*\+\s*"([^"]+)"',
    ]

    for pattern in col_patterns:
        for m in re.finditer(pattern, body):
            col = m.group(1).strip()
            # Must look like a SQL column identifier
            if not valid_col_re.match(col):
                continue
            # Skip Java error message strings and other non-column content
            if len(col) > 40:
                continue
            columns.append(col)

    # Deduplicate while preserving order
    seen = set()
    unique = []
    for c in columns:
        key = c.lower()
        if key not in seen:
            seen.add(key)
            unique.append(c)

    return unique


# ─── Caller tracing ─────────────────────────────────────────────────────────

def find_procedure_call_chains(dao_dir: Path) -> list:
    """Find Java methods that call a stored procedure and then a hydration method."""
    chains = []

    if not dao_dir.exists():
        return chains

    # Pattern for procedure calls: prepareStatement("SELECT * FROM starexec.ProcName(...)")
    proc_call_re = re.compile(
        r'prepareStatement\s*\(\s*"[^"]*starexec\.(\w+)\s*\(',
        re.IGNORECASE,
    )
    # Pattern for hydration calls
    hydration_call_re = re.compile(
        r"(\w+\.)?(?:resultTo\w+|resultSetTo\w+|fromResultSet)\s*\(",
    )

    for java_file in sorted(dao_dir.glob("*.java")):
        text = java_file.read_text(encoding="utf-8")
        lines = text.split("\n")

        # Find each method that contains a procedure call
        current_method = None
        current_proc = None
        brace_depth = 0

        for i, line in enumerate(lines):
            # Track method boundaries (rough heuristic)
            method_sig = re.search(
                r"(?:public|protected|private)\s+(?:static\s+)?[\w<>\[\]]+\s+(\w+)\s*\(",
                line,
            )
            if method_sig and "{" in line:
                current_method = method_sig.group(1)

            proc_match = proc_call_re.search(line)
            if proc_match:
                current_proc = proc_match.group(1)

            hydration_match = hydration_call_re.search(line)
            if hydration_match and current_proc:
                hydration_name = hydration_match.group(0).rstrip("(").strip()
                if "." in hydration_name:
                    hydration_name = hydration_name.split(".")[-1]

                chains.append(CallerInfo(
                    file=java_file.name,
                    line=i + 1,
                    java_method=current_method or "<unknown>",
                    procedure_called=current_proc.lower(),
                    hydration_method=hydration_name,
                ))

    return chains


# ─── Suppression file ───────────────────────────────────────────────────────

def load_suppressions(path: Path) -> set:
    """Load suppressed mismatch keys from a .dto-audit-suppress file.

    Format: one entry per line, either:
      procedure:column          — suppress a specific column mismatch
      procedure:dto:column      — suppress an inline gap field
      # comment lines
      blank lines
    """
    suppressions = set()
    if not path.exists():
        return suppressions
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        suppressions.add(line.lower())
    return suppressions


# ─── Cross-reference analysis ───────────────────────────────────────────────

def find_mismatches(
    procedures: dict,
    hydration_methods: dict,
    call_chains: list,
) -> list:
    """Cross-reference procedure columns with hydration method reads."""
    mismatches = []

    # Build lookup: hydration_method_name -> HydrationSite
    hydration_by_name = {}
    for key, site in hydration_methods.items():
        hydration_by_name.setdefault(site.method_name, []).append(site)

    for chain in call_chains:
        proc = procedures.get(chain.procedure_called)
        if not proc:
            continue

        # Find the hydration method
        sites = hydration_by_name.get(chain.hydration_method, [])
        if not sites:
            continue

        proc_cols = set(proc.return_columns)

        # If procedure returns *, we can't check
        if "*" in proc_cols:
            continue

        for site in sites:
            for col_read in site.columns_read:
                col_lower = col_read.lower()

                # Strip common prefixes that hydration methods add
                bare_col = col_lower
                for prefix in ("bench.", "bench_", "solvers.", "solvers_",
                               "s.", "s_", "c.", "c_", "types.", "types_",
                               "config.", "config_"):
                    if bare_col.startswith(prefix):
                        bare_col = bare_col[len(prefix):]

                # Check if column exists in procedure output
                if (col_lower not in proc_cols and
                    bare_col not in proc_cols and
                    not any(col_lower in pc or bare_col in pc for pc in proc_cols)):

                    # Determine severity: if the hydration code throws on null, it's ERROR
                    severity = "WARNING"
                    for s in sites:
                        body_text = " ".join(s.columns_read)  # rough heuristic
                    if "required" in chain.hydration_method.lower():
                        severity = "ERROR"

                    mismatches.append(Mismatch(
                        hydration_method=f"{site.file}::{site.method_name}",
                        hydration_file=site.file,
                        column_read=col_read,
                        procedure=proc.name,
                        procedure_line=proc.line,
                        severity=severity,
                    ))

    return mismatches


# ─── Standalone procedure column report ─────────────────────────────────────

def find_inline_hydration_gaps(procedures: dict, dao_dir: Path) -> list:
    """
    For procedures whose results are consumed by *inline* hydration
    (e.g. getPairsDetailed building Solver/Benchmark stubs manually),
    compare the procedure's SELECT list against the full DTO hydration
    method's column expectations.
    """
    gaps = []

    # Known high-value mappings: procedure -> (hydration method, DTO name, expected columns)
    # These are the patterns we *know* from manual analysis.  The script
    # validates them automatically so new regressions are caught.
    KNOWN_MAPPINGS = [
        {
            "procedures": ["getalljobpairsbyjob", "getnewcompletedjobpairsbyjob"],
            "dto": "Solver",
            "inline_fields_set": ["id", "name"],
            "full_hydration_fields": [
                "id", "user_id", "name", "uploaded", "path", "description",
                "downloadable", "disk_size", "executable_type", "recycled",
                "deleted", "build_status",
            ],
        },
        {
            "procedures": ["getalljobpairsbyjob", "getnewcompletedjobpairsbyjob"],
            "dto": "Benchmark",
            "inline_fields_set": ["id", "name"],
            "full_hydration_fields": [
                "id", "user_id", "name", "uploaded", "path", "description",
                "downloadable", "disk_size", "recycled", "deleted",
            ],
        },
        {
            "procedures": ["getalljobpairsbyjob", "getnewcompletedjobpairsbyjob"],
            "dto": "WorkerNode",
            "inline_fields_set": ["id", "name", "status"],
            "full_hydration_fields": ["id", "name", "status"],
        },
    ]

    for mapping in KNOWN_MAPPINGS:
        for proc_name in mapping["procedures"]:
            proc = procedures.get(proc_name)
            if not proc:
                continue

            proc_cols = set(proc.return_columns)
            available_for_dto = []
            missing_from_select = []

            for f in mapping["full_hydration_fields"]:
                # Check various aliases
                found = False
                for candidate in [f, f"{mapping['dto'].lower()}_{f}",
                                  f"config_{f}", f"bench_{f}", f"solver_{f}"]:
                    if candidate in proc_cols:
                        found = True
                        break

                if found:
                    available_for_dto.append(f)
                else:
                    missing_from_select.append(f)

            gaps.append({
                "procedure": proc.name,
                "procedure_line": proc.line,
                "dto": mapping["dto"],
                "fields_hydrated_inline": mapping["inline_fields_set"],
                "fields_available_in_select": available_for_dto,
                "fields_missing_from_select": missing_from_select,
                "full_dto_fields": mapping["full_hydration_fields"],
            })

    return gaps


# ─── Report Generation ──────────────────────────────────────────────────────

def print_text_report(
    procedures: dict,
    hydration_methods: dict,
    call_chains: list,
    mismatches: list,
    inline_gaps: list,
    verbose: bool,
):
    """Print a human-readable audit report."""
    W = 90

    print("=" * W)
    print("  StarExec DTO Hydration Audit Report")
    print("=" * W)
    print()

    # Summary
    print(f"Stored procedures parsed:    {len(procedures)}")
    print(f"Hydration methods found:     {len(hydration_methods)}")
    print(f"Procedure→Hydration chains:  {len(call_chains)}")
    print(f"Column mismatches detected:  {len(mismatches)}")
    print(f"Inline hydration gaps:       {len(inline_gaps)}")
    print()

    # ── Section 1: Inline hydration gaps (the high-value finding) ──
    if inline_gaps:
        print("-" * W)
        print("  INLINE HYDRATION GAPS")
        print("  (Procedures whose results pass through manual stub hydration,")
        print("   not the full resultTo* method. DTO fields left at Java defaults.)")
        print("-" * W)
        print()

        for gap in inline_gaps:
            status = "OK" if not gap["fields_missing_from_select"] else "GAP"
            print(f"  [{status}] {gap['procedure']}() → {gap['dto']}")
            print(f"        SQL file line: {gap['procedure_line']}")
            print(f"        Fields hydrated inline:     {gap['fields_hydrated_inline']}")
            print(f"        Available in SELECT:         {gap['fields_available_in_select']}")
            if gap["fields_missing_from_select"]:
                print(f"        ❌ MISSING from SELECT:      {gap['fields_missing_from_select']}")
                print(f"        → These fields default to Java zero/null on the {gap['dto']} DTO.")
                print(f"          Any caller accessing them gets UNINITIALIZED DATA.")
            else:
                print(f"        All full-hydration fields are available in the SELECT.")
            print()

    # ── Section 2: Procedure→hydration call chains ──
    if call_chains and verbose:
        print("-" * W)
        print("  PROCEDURE → HYDRATION CALL CHAINS")
        print("-" * W)
        print()

        for chain in call_chains:
            proc = procedures.get(chain.procedure_called)
            col_count = len(proc.return_columns) if proc else "?"
            print(f"  {chain.file}:{chain.line}")
            print(f"    {chain.java_method}()")
            print(f"    → SQL: starexec.{chain.procedure_called}()  [{col_count} columns]")
            print(f"    → Java: {chain.hydration_method}()")
            print()

    # ── Section 3: Formal column mismatches ──
    if mismatches:
        print("-" * W)
        print("  COLUMN MISMATCHES")
        print("  (Hydration method reads a column not in the procedure's SELECT)")
        print("-" * W)
        print()

        # Group by procedure
        by_proc = defaultdict(list)
        for mm in mismatches:
            by_proc[mm.procedure].append(mm)

        for proc_name, mms in sorted(by_proc.items()):
            print(f"  Procedure: {proc_name} (line {mms[0].procedure_line})")
            for mm in mms:
                marker = "!!" if mm.severity == "ERROR" else "??"
                print(f"    [{marker}] {mm.hydration_method} reads '{mm.column_read}'")
            print()

    # ── Section 4: Procedure column inventory (verbose) ──
    if verbose:
        print("-" * W)
        print("  STORED PROCEDURE COLUMN INVENTORY")
        print("-" * W)
        print()

        # Only show procedures that return data (have columns)
        returning = {k: v for k, v in procedures.items() if v.return_columns}
        for name in sorted(returning):
            proc = returning[name]
            print(f"  {proc.name} (line {proc.line})")
            print(f"    Columns: {', '.join(proc.return_columns)}")
            print()

    # ── Recommendations ──
    print("-" * W)
    print("  RECOMMENDATIONS")
    print("-" * W)
    print()

    danger_dtos = set()
    for gap in inline_gaps:
        if gap["fields_missing_from_select"]:
            danger_dtos.add((gap["procedure"], gap["dto"]))

    if danger_dtos:
        print("  1. HIGH PRIORITY — Unhydrated fields accessed downstream:")
        print()
        for proc, dto in sorted(danger_dtos):
            print(f"     • {proc}() → {dto}: callers that access fields beyond")
            print(f"       id/name will get Java default values (0/null/false).")
            print(f"       → Audit all callers of getPairsDetailed() for {dto} field access.")
            print()

        print("  2. MEDIUM PRIORITY — Add column-contract documentation:")
        print()
        print("     Each stored procedure should have a comment documenting which")
        print("     DTO it feeds and which fields will be populated. Example:")
        print()
        print("     -- @feeds: Solver(id, name) — stub only; user_id, path, etc. NOT populated")
        print("     -- @feeds: JobPair(id, jobId, statusCode, path, ...) — full hydration")
        print()

        print("  3. LOW PRIORITY — Long-term DTO split:")
        print()
        print("     Consider introducing projection-specific DTOs (e.g., SolverStub vs Solver)")
        print("     so the type system prevents accessing unhydrated fields at compile time.")
        print()
    else:
        print("  No critical gaps detected. Procedure columns cover all hydrated fields.")
        print()

    print("=" * W)
    print("  End of report")
    print("=" * W)


def print_json_report(
    procedures: dict,
    hydration_methods: dict,
    call_chains: list,
    mismatches: list,
    inline_gaps: list,
):
    """Print a machine-readable JSON report."""
    report = {
        "summary": {
            "procedures_parsed": len(procedures),
            "hydration_methods_found": len(hydration_methods),
            "call_chains_traced": len(call_chains),
            "column_mismatches": len(mismatches),
            "inline_hydration_gaps": len(inline_gaps),
        },
        "inline_gaps": inline_gaps,
        "mismatches": [asdict(m) for m in mismatches],
        "call_chains": [asdict(c) for c in call_chains],
    }
    print(json.dumps(report, indent=2))


# ─── Main ───────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="Audit StarExec DTO hydration coverage against stored procedure SELECT lists."
    )
    parser.add_argument("--json", action="store_true", help="Output as JSON instead of text")
    parser.add_argument("--verbose", "-v", action="store_true", help="Include full procedure inventory and call chains")
    parser.add_argument("--ci", action="store_true",
                        help="CI mode: only gate on inline hydration gaps (high confidence). "
                             "Column mismatches are reported but do not affect exit code.")
    parser.add_argument("--suppress-file", type=Path, default=SUPPRESSION_FILE,
                        help="Path to suppression file (.dto-audit-suppress)")
    parser.add_argument("--sql-file", type=Path, default=SQL_FILE, help="Path to R__procedures_and_views.sql")
    parser.add_argument("--dao-dir", type=Path, default=DAO_DIR, help="Path to DAO Java source directory")
    args = parser.parse_args()

    # Load suppressions
    suppressions = load_suppressions(args.suppress_file)

    # Parse both sides
    procedures = parse_procedures(args.sql_file)
    hydration_methods = parse_hydration_methods(args.dao_dir)
    call_chains = find_procedure_call_chains(args.dao_dir)

    # Cross-reference
    mismatches = find_mismatches(procedures, hydration_methods, call_chains)
    inline_gaps = find_inline_hydration_gaps(procedures, args.dao_dir)

    # Apply suppressions to inline gaps
    for gap in inline_gaps:
        proc_lower = gap["procedure"].lower()
        dto_lower = gap["dto"].lower()
        unsuppressed = []
        for f in gap["fields_missing_from_select"]:
            key = f"{proc_lower}:{dto_lower}:{f.lower()}"
            if key not in suppressions:
                unsuppressed.append(f)
        gap["fields_suppressed"] = [
            f for f in gap["fields_missing_from_select"] if f not in unsuppressed
        ]
        gap["fields_missing_from_select"] = unsuppressed

    # Apply suppressions to column mismatches
    unsuppressed_mismatches = []
    for mm in mismatches:
        key = f"{mm.procedure.lower()}:{mm.column_read.lower()}"
        if key not in suppressions:
            unsuppressed_mismatches.append(mm)
    suppressed_count = len(mismatches) - len(unsuppressed_mismatches)
    mismatches = unsuppressed_mismatches

    if args.json:
        print_json_report(procedures, hydration_methods, call_chains, mismatches, inline_gaps)
    else:
        print_text_report(procedures, hydration_methods, call_chains, mismatches, inline_gaps, args.verbose)
        if suppressed_count > 0:
            print(f"  ({suppressed_count} column mismatches suppressed via {args.suppress_file.name})")
            print()

    # Exit code logic
    if args.ci:
        # CI mode: only inline gaps (high confidence) affect exit code
        critical = any(g["fields_missing_from_select"] for g in inline_gaps)
    else:
        # Full mode: inline gaps OR ERROR-severity mismatches
        critical = any(g["fields_missing_from_select"] for g in inline_gaps) or \
                   any(m.severity == "ERROR" for m in mismatches)
    sys.exit(1 if critical else 0)


if __name__ == "__main__":
    main()
