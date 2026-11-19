#!/usr/bin/env python3
"""Post-load validator for legacy MariaDB -> Flyway PostgreSQL migrations.

Checks:
  1. Per-table row-count parity between MariaDB (source) and PostgreSQL (target).
  2. Password wrapping invariant: zero unwrapped legacy hashes remain in users.
  3. Sequence safety: every SERIAL/IDENTITY sequence is >= MAX(column).
  4. Staging safety: system_flags.paused is true.

Credentials are read from the same environment variables as migrate.py.
"""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import sys
from typing import Dict, List, Optional, Sequence, Tuple

from scripts.mysql2pg import migrate


def evaluate_count_parity(
    source_counts: Dict[str, int],
    target_counts: Dict[str, int],
) -> List[str]:
    """Return list of human-readable count mismatch errors."""
    errors: List[str] = []
    for table in sorted(target_counts):
        if table not in source_counts:
            errors.append(f"{table}: missing source count")
            continue
        src = source_counts[table]
        tgt = target_counts[table]
        if src != tgt:
            errors.append(f"{table}: row count mismatch (source={src}, target={tgt})")
    return errors


def evaluate_sequence_bounds(
    sequence_states: Sequence[Tuple[str, str, int, int, bool]],
) -> List[str]:
    """Check each (table, seq_name, max_id, last_value, is_called) tuple."""
    errors: List[str] = []
    for table, seq_name, max_id, last_val, is_called in sequence_states:
        if max_id > 0 and (last_val < max_id or not is_called):
            errors.append(
                f"{table} ({seq_name}): sequence behind data (max_id={max_id}, last_value={last_val}, is_called={is_called})"
            )
    return errors


def fetch_mysql_counts(my_conn, db_name: str, tables: Sequence[str]) -> Dict[str, int]:
    counts: Dict[str, int] = {}
    with my_conn.cursor() as cur:
        for table in tables:
            cur.execute(
                f"SELECT COUNT(*) FROM {migrate._quote_mysql_ident(db_name)}.{migrate._quote_mysql_ident(table)}"
            )
            rows = cur.fetchall()
            counts[table] = int(rows[0][0])
    return counts


def fetch_pg_counts(pg_conn, schema: str, tables: Sequence[str]) -> Dict[str, int]:
    counts: Dict[str, int] = {}
    with pg_conn.cursor() as cur:
        for table in tables:
            cur.execute(
                f"SELECT COUNT(*) FROM {migrate._quote_ident(schema)}.{migrate._quote_ident(table)}"
            )
            counts[table] = int(cur.fetchone()[0])
    return counts


def fetch_pg_sequence_states(
    pg_conn, schema: str, tables: Sequence[str]
) -> List[Tuple[str, str, int, int, bool]]:
    states: List[Tuple[str, str, int, int, bool]] = []
    with pg_conn.cursor() as cur:
        for table in tables:
            cur.execute(
                """
                SELECT column_name, pg_get_serial_sequence(%s, column_name)
                FROM information_schema.columns
                WHERE table_schema = %s AND table_name = %s
                """,
                (f"{schema}.{table}", schema, table),
            )
            for col, seq in cur.fetchall():
                if not seq:
                    continue
                cur.execute(
                    f"SELECT COALESCE(MAX({migrate._quote_ident(col)}), 0) "
                    f"FROM {migrate._quote_ident(schema)}.{migrate._quote_ident(table)}"
                )
                max_id = int(cur.fetchone()[0])
                cur.execute(f"SELECT last_value, is_called FROM {seq}")
                last_val, is_called = cur.fetchone()
                states.append((table, seq, max_id, int(last_val), bool(is_called)))
    return states


def check_unwrapped_passwords(pg_conn, schema: str) -> int:
    with pg_conn.cursor() as cur:
        cur.execute(
            f"""
            SELECT COUNT(*) FROM {migrate._quote_ident(schema)}.users
            WHERE password IS NULL
               OR (password !~ '^\\$2[aby]\\$' AND password NOT LIKE '{{sha512}}$2%')
            """
        )
        return int(cur.fetchone()[0])


def check_scheduler_paused(pg_conn, schema: str) -> bool:
    with pg_conn.cursor() as cur:
        cur.execute(f"SELECT paused FROM {migrate._quote_ident(schema)}.system_flags LIMIT 1")
        row = cur.fetchone()
        return bool(row and row[0])


def parse_args(argv: Optional[Sequence[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Validate MariaDB -> PostgreSQL migration output.")
    parser.add_argument("--manifest", type=Path, default=migrate.DEFAULT_MANIFEST)
    parser.add_argument("--tier", type=int, action="append")
    parser.add_argument("--table", action="append")
    parser.add_argument("--include-optional", action="store_true")
    parser.add_argument(
        "--skip-source-check",
        action="store_true",
        help="Validate PostgreSQL target invariants only without connecting to MariaDB",
    )
    return parser.parse_args(argv)


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = parse_args(argv)
    manifest = migrate.load_manifest(args.manifest)
    schema = manifest["schema"]
    planned = migrate.plan_tables(
        manifest,
        tiers=args.tier,
        only_tables=args.table,
        include_optional=args.include_optional,
    )
    table_names = [t["name"] for t in planned]
    errors: List[str] = []

    pg_conn = migrate.connect_postgres()
    my_conn = None
    try:
        pg_counts = fetch_pg_counts(pg_conn, schema, table_names)
        if not args.skip_source_check:
            my_conn = migrate.connect_mysql(manifest.get("source_timezone", "+00:00"))
            my_counts = fetch_mysql_counts(
                my_conn, os.environ.get("MYSQL_DB", "starexec"), table_names
            )
            errors.extend(evaluate_count_parity(my_counts, pg_counts))

        seq_states = fetch_pg_sequence_states(pg_conn, schema, table_names)
        errors.extend(evaluate_sequence_bounds(seq_states))

        if "users" in pg_counts:
            bad_pw = check_unwrapped_passwords(pg_conn, schema)
            if bad_pw > 0:
                errors.append(f"users: {bad_pw} account(s) have unwrapped or unusable password hashes")

        if "system_flags" in pg_counts and pg_counts["system_flags"] > 0:
            if not check_scheduler_paused(pg_conn, schema):
                errors.append("system_flags: expected paused = true on staging import")
    finally:
        pg_conn.close()
        if my_conn is not None:
            my_conn.close()

    if errors:
        print("VALIDATION FAILED:")
        for err in errors:
            print(f"  - {err}")
        return 1

    print(f"VALIDATION PASSED ({len(table_names)} tables verified)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
