#!/usr/bin/env python3
"""Streaming data-only ETL from legacy MariaDB (se-m1) to Flyway PostgreSQL.

Source access is strictly read-only (SELECT). Target schema must already exist
from Flyway migrations; only shared columns are copied, leaving target-only
Flyway columns at their defaults.

Credentials are read exclusively from environment variables:
  Source (MySQL/MariaDB): MYSQL_HOST, MYSQL_PORT (3306), MYSQL_USER,
                          MYSQL_PASSWORD, MYSQL_DB (starexec)
  Target (PostgreSQL):    DATABASE_URL  (or PGHOST, PGPORT, PGUSER,
                          PGPASSWORD, PGDATABASE)
"""

from __future__ import annotations

import argparse
import datetime
import io
import os
from pathlib import Path
import sys
from typing import Any, Dict, Iterable, List, Optional, Sequence, Set, Tuple

import yaml

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_MANIFEST = REPO_ROOT / "scripts" / "mysql2pg" / "manifest.yaml"

PG_DATE_TYPES = {
    "date",
    "timestamp without time zone",
    "timestamp with time zone",
}


def load_manifest(path: Path) -> Dict[str, Any]:
    return yaml.safe_load(path.read_text(encoding="utf-8"))


def plan_tables(
    manifest: Dict[str, Any],
    tiers: Optional[Sequence[int]] = None,
    only_tables: Optional[Sequence[str]] = None,
    include_optional: bool = False,
) -> List[Dict[str, Any]]:
    """Return ordered table specs selected for this run."""
    selected_tiers = set(tiers) if tiers is not None else {0, 1, 2, 3}
    only_set = set(only_tables) if only_tables else None
    planned: List[Dict[str, Any]] = []

    for tier_num in sorted(manifest["tiers"].keys()):
        if tier_num not in selected_tiers and only_set is None:
            continue
        tier_cfg = manifest["tiers"][tier_num]
        default_action = tier_cfg.get("default_action", "load")
        for entry in tier_cfg["tables"]:
            name = entry["name"]
            action = entry.get("action", default_action)
            if only_set is not None:
                if name not in only_set:
                    continue
            else:
                if action == "drop":
                    continue
                if (action == "optional" or entry.get("optional", False)) and not include_optional:
                    continue
            spec = dict(entry)
            spec["tier"] = tier_num
            planned.append(spec)

    if only_set is not None:
        found = {t["name"] for t in planned}
        missing = sorted(only_set - found)
        if missing:
            raise ValueError(f"Unknown table(s) not in manifest: {', '.join(missing)}")

    return planned


def is_zero_date(val: Any) -> bool:
    if isinstance(val, (bytes, bytearray)):
        try:
            val = val.decode("utf-8", errors="ignore")
        except Exception:
            return False
    if isinstance(val, str):
        return val.startswith("0000-00-00")
    return False


def to_pg_bool(val: Any) -> Optional[bool]:
    if val is None:
        return None
    if isinstance(val, bool):
        return val
    if isinstance(val, (int, float)):
        return bool(val)
    if isinstance(val, (bytes, bytearray)):
        if len(val) == 1:
            return val[0] != 0
        return val.decode("utf-8", errors="ignore").strip().lower() in {"1", "true", "t", "yes"}
    if isinstance(val, str):
        return val.strip().lower() in {"1", "true", "t", "yes"}
    return bool(val)


def transform_row(
    row: Sequence[Any],
    columns: Sequence[str],
    pg_col_types: Dict[str, str],
    zero_date_cols: Set[str],
    overrides: Dict[str, Any],
    deferred_fk_cols: Set[str],
) -> Tuple[List[Any], Dict[str, Any]]:
    """Transform one MySQL row for PostgreSQL COPY.

    Returns (transformed_values, deferred_fk_values_dict).
    """
    out: List[Any] = []
    deferred_vals: Dict[str, Any] = {}

    for col, raw in zip(columns, row):
        val = overrides[col] if col in overrides else raw

        if col in deferred_fk_cols:
            if val is not None:
                deferred_vals[col] = val
            out.append(None)
            continue

        pg_type = pg_col_types.get(col, "")
        if pg_type == "boolean":
            val = to_pg_bool(val)
        elif col in zero_date_cols or pg_type in PG_DATE_TYPES:
            if is_zero_date(val):
                val = None

        out.append(val)

    return out, deferred_vals


def format_copy_field(val: Any) -> str:
    """Format a Python value for PostgreSQL COPY TEXT format (NULL = \\N)."""
    if val is None:
        return r"\N"
    if isinstance(val, bool):
        return "t" if val else "f"
    if isinstance(val, (datetime.datetime, datetime.date)):
        return val.isoformat(sep=" ") if isinstance(val, datetime.datetime) else val.isoformat()
    if isinstance(val, (bytes, bytearray)):
        text = val.decode("utf-8", errors="replace")
    else:
        text = str(val)
    # PostgreSQL COPY TEXT escaping: backslash, tab, newline, carriage return
    text = text.replace("\\", "\\\\")
    text = text.replace("\t", "\\t")
    text = text.replace("\n", "\\n")
    text = text.replace("\r", "\\r")
    # Strip NUL bytes (PostgreSQL text columns reject 0x00)
    text = text.replace("\x00", "")
    return text


def format_copy_line(values: Iterable[Any]) -> str:
    return "\t".join(format_copy_field(v) for v in values) + "\n"


def _quote_ident(ident: str) -> str:
    return '"' + ident.replace('"', '""') + '"'


def _quote_mysql_ident(ident: str) -> str:
    return "`" + ident.replace("`", "``") + "`"


def connect_mysql(source_tz: str):
    import pymysql  # Lazy import so unit tests run without DB drivers

    conn = pymysql.connect(
        host=os.environ.get("MYSQL_HOST", "127.0.0.1"),
        port=int(os.environ.get("MYSQL_PORT", "3306")),
        user=os.environ["MYSQL_USER"],
        password=os.environ["MYSQL_PASSWORD"],
        database=os.environ.get("MYSQL_DB", "starexec"),
        charset="utf8mb4",
        cursorclass=pymysql.cursors.SSCursor,
        autocommit=True,
    )
    with conn.cursor() as cur:
        cur.execute("SET SESSION time_zone = %s", (source_tz,))
        cur.execute("SET SESSION net_write_timeout = 3600")
        cur.execute("SET SESSION net_read_timeout = 3600")
        try:
            cur.execute("SET SESSION TRANSACTION READ ONLY")
        except pymysql.err.OperationalError as exc:
            # MariaDB 5.5 does not support SET SESSION TRANSACTION READ ONLY (errno 1193).
            if not exc.args or exc.args[0] != 1193:
                raise
    return conn


def connect_postgres():
    import psycopg2  # Lazy import so unit tests run without DB drivers

    dsn = os.environ.get("DATABASE_URL")
    if dsn:
        conn = psycopg2.connect(dsn)
    else:
        conn = psycopg2.connect(
            host=os.environ.get("PGHOST", "127.0.0.1"),
            port=int(os.environ.get("PGPORT", "5432")),
            user=os.environ.get("PGUSER", "starexec"),
            password=os.environ.get("PGPASSWORD", ""),
            dbname=os.environ.get("PGDATABASE", "starexec"),
        )
    conn.autocommit = True
    with conn.cursor() as cur:
        # Session-level async commit cuts bulk COPY WAL fsync stalls without altering target config.
        cur.execute("SET SESSION synchronous_commit = off")
    conn.autocommit = False
    return conn


def fetch_mysql_columns(my_conn, db_name: str, table: str) -> List[str]:
    with my_conn.cursor() as cur:
        cur.execute(
            """
            SELECT LOWER(column_name)
            FROM information_schema.columns
            WHERE table_schema = %s AND LOWER(table_name) = %s
            ORDER BY ordinal_position
            """,
            (db_name, table.lower()),
        )
        return [r[0] for r in cur.fetchall()]


def fetch_pg_column_types(pg_conn, schema: str, table: str) -> Dict[str, str]:
    with pg_conn.cursor() as cur:
        cur.execute(
            """
            SELECT LOWER(column_name), data_type
            FROM information_schema.columns
            WHERE table_schema = %s AND LOWER(table_name) = %s
            ORDER BY ordinal_position
            """,
            (schema, table.lower()),
        )
        return {r[0]: r[1] for r in cur.fetchall()}


def fetch_pg_primary_key(pg_conn, schema: str, table: str) -> List[str]:
    with pg_conn.cursor() as cur:
        cur.execute(
            """
            SELECT LOWER(a.attname)
            FROM pg_index i
            JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
            WHERE i.indrelid = %s::regclass AND i.indisprimary
            ORDER BY array_position(i.indkey, a.attnum)
            """,
            (f"{schema}.{table}",),
        )
        return [r[0] for r in cur.fetchall()]


def sync_sequences(pg_conn, schema: str, tables: Sequence[str]) -> int:
    """Advance all SERIAL/IDENTITY sequences on loaded tables to MAX(col)."""
    updated = 0
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
                    f"SELECT COALESCE(MAX({_quote_ident(col)}), 0) FROM {_quote_ident(schema)}.{_quote_ident(table)}"
                )
                max_val = cur.fetchone()[0]
                if max_val > 0:
                    cur.execute("SELECT setval(%s, %s, true)", (seq, max_val))
                else:
                    cur.execute("SELECT setval(%s, 1, false)", (seq,))
                updated += 1
    pg_conn.commit()
    return updated


def split_resume_tables(
    planned_tables: Sequence[Dict[str, Any]],
    resume_from: Optional[str],
) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]]]:
    """Split planned_tables into (already_loaded, remaining_to_load)."""
    if not resume_from:
        return [], list(planned_tables)
    for idx, spec in enumerate(planned_tables):
        if spec["name"] == resume_from:
            return list(planned_tables[:idx]), list(planned_tables[idx:])
    raise ValueError(f"Resume table {resume_from!r} is not in the planned table list")


def collect_deferred_backfills(
    my_conn,
    mysql_db: str,
    pg_conn,
    schema: str,
    table: str,
    deferred_fk_cols: Set[str],
) -> Optional[Tuple[str, List[str], List[Tuple[Tuple[Any, ...], Dict[str, Any]]]]]:
    """Fetch PK -> deferred FK mappings from MariaDB for an already-loaded table."""
    if not deferred_fk_cols:
        return None
    pk_cols = fetch_pg_primary_key(pg_conn, schema, table)
    cols = pk_cols + sorted(deferred_fk_cols)
    select_list = ", ".join(_quote_mysql_ident(c) for c in cols)
    rows_to_update: List[Tuple[Tuple[Any, ...], Dict[str, Any]]] = []
    with my_conn.cursor() as cur:
        cur.execute(
            f"SELECT {select_list} FROM {_quote_mysql_ident(mysql_db)}.{_quote_mysql_ident(table)}"
        )
        for row in cur:
            pk_tuple = tuple(row[: len(pk_cols)])
            fk_map = {
                col: val
                for col, val in zip(cols[len(pk_cols) :], row[len(pk_cols) :])
                if val is not None
            }
            if fk_map:
                rows_to_update.append((pk_tuple, fk_map))
    if not rows_to_update:
        return None
    return (table, pk_cols, rows_to_update)


def clear_target_tables(pg_conn, schema: str, planned_tables: Sequence[Dict[str, Any]]) -> None:
    """Clear target tables via DELETE in reverse FK order.

    DELETE is used instead of TRUNCATE ... CASCADE because Flyway V0135+
    attaches immutability BEFORE TRUNCATE triggers on execution history tables.
    """
    with pg_conn.cursor() as cur:
        for spec in reversed(planned_tables):
            table = spec["name"]
            cur.execute(f"DELETE FROM {_quote_ident(schema)}.{_quote_ident(table)}")
    pg_conn.commit()
    print(f"[clear-seed] cleared {len(planned_tables)} target tables via DELETE")


def run_migration(
    manifest: Dict[str, Any],
    planned_tables: Sequence[Dict[str, Any]],
    chunk_size: int = 5000,
    dry_run: bool = False,
    skip_post_sql: bool = False,
    clear_seed: bool = False,
    resume_from: Optional[str] = None,
) -> None:
    if clear_seed and resume_from:
        raise ValueError("--clear-seed and --resume-from cannot be used together")

    schema = manifest["schema"]
    source_tz = manifest.get("source_timezone", "+00:00")
    mysql_db = os.environ.get("MYSQL_DB", "starexec")
    already_loaded, tables_to_load = split_resume_tables(planned_tables, resume_from)

    if dry_run:
        print(f"[DRY-RUN] Target schema: {schema} | Source TZ: {source_tz}")
        if resume_from:
            print(f"[DRY-RUN] Resuming from table: {resume_from} (skipping {len(already_loaded)} pre-loaded tables)")
        print(f"[DRY-RUN] Planned tables ({len(tables_to_load)}):")
        for spec in tables_to_load:
            extras = []
            if spec.get("deferred_fk_columns"):
                extras.append(f"deferred_fk={spec['deferred_fk_columns']}")
            if spec.get("zero_date_columns"):
                extras.append(f"zero_dates={spec['zero_date_columns']}")
            if spec.get("overrides"):
                extras.append(f"overrides={spec['overrides']}")
            if spec.get("dedupe_on"):
                extras.append(f"dedupe_on={spec['dedupe_on']}")
            suffix = f" ({', '.join(extras)})" if extras else ""
            print(f"  [tier {spec['tier']}] {spec['name']}{suffix}")
        if not skip_post_sql:
            for rel in manifest.get("post_load_sql", []):
                print(f"[DRY-RUN] Post-load SQL: {rel}")
        return

    my_conn = connect_mysql(source_tz)
    pg_conn = connect_postgres()
    deferred_backfills: List[Tuple[str, List[str], List[Tuple[Tuple[Any, ...], Dict[str, Any]]]]] = []
    loaded_names: List[str] = []

    try:
        if clear_seed:
            clear_target_tables(pg_conn, schema, planned_tables)
        elif resume_from:
            # Clear the resume boundary and all remaining tables so no prior partial rows collide.
            clear_target_tables(pg_conn, schema, tables_to_load)

        for pre_spec in already_loaded:
            pre_table = pre_spec["name"]
            loaded_names.append(pre_table)
            deferred_cols = set(pre_spec.get("deferred_fk_columns", []))
            backfill = collect_deferred_backfills(
                my_conn, mysql_db, pg_conn, schema, pre_table, deferred_cols
            )
            if backfill is not None:
                deferred_backfills.append(backfill)
            print(f"[resume] skipping pre-loaded table {pre_table}")

        for spec in tables_to_load:
            table = spec["name"]
            my_cols = fetch_mysql_columns(my_conn, mysql_db, table)
            pg_types = fetch_pg_column_types(pg_conn, schema, table)
            shared_cols = [c for c in my_cols if c in pg_types]
            if not shared_cols:
                raise RuntimeError(f"No shared columns found for table {table}")

            zero_date_cols = set(spec.get("zero_date_columns", []))
            overrides = dict(spec.get("overrides", {}))
            deferred_fk_cols = set(spec.get("deferred_fk_columns", []))
            dedupe_cols = list(spec.get("dedupe_on", []))
            for dc in dedupe_cols:
                if dc not in shared_cols:
                    raise RuntimeError(f"{table}: dedupe_on column {dc!r} not in shared columns")
            dedupe_indices = [shared_cols.index(dc) for dc in dedupe_cols]
            seen_keys: Optional[Set[Tuple[Any, ...]]] = set() if dedupe_indices else None

            pk_cols = fetch_pg_primary_key(pg_conn, schema, table) if deferred_fk_cols else []
            pk_indices = [shared_cols.index(c) for c in pk_cols]
            table_backfills: List[Tuple[Tuple[Any, ...], Dict[str, Any]]] = []

            select_list = ", ".join(
                (
                    f"NULLIF(CAST({_quote_mysql_ident(c)} AS CHAR), '0000-00-00 00:00:00')"
                    if (c in zero_date_cols or pg_types[c] in PG_DATE_TYPES)
                    else _quote_mysql_ident(c)
                )
                for c in shared_cols
            )
            copy_cols = ", ".join(_quote_ident(c) for c in shared_cols)
            copy_sql = (
                f"COPY {_quote_ident(schema)}.{_quote_ident(table)} ({copy_cols}) "
                "FROM STDIN WITH (FORMAT text, NULL '\\N')"
            )

            total_rows = 0
            skipped_dupes = 0
            with my_conn.cursor() as my_cur, pg_conn.cursor() as pg_cur:
                my_cur.execute(
                    f"SELECT {select_list} FROM {_quote_mysql_ident(mysql_db)}.{_quote_mysql_ident(table)}"
                )
                buf = io.StringIO()
                batch_rows = 0
                for raw_row in my_cur:
                    if seen_keys is not None:
                        key = tuple(raw_row[idx] for idx in dedupe_indices)
                        if key in seen_keys:
                            skipped_dupes += 1
                            continue
                        seen_keys.add(key)
                    vals, deferred_vals = transform_row(
                        raw_row, shared_cols, pg_types, zero_date_cols, overrides, deferred_fk_cols
                    )
                    if deferred_vals:
                        pk_tuple = tuple(raw_row[idx] for idx in pk_indices)
                        table_backfills.append((pk_tuple, deferred_vals))
                    buf.write(format_copy_line(vals))
                    batch_rows += 1
                    total_rows += 1
                    if batch_rows >= chunk_size:
                        buf.seek(0)
                        pg_cur.copy_expert(copy_sql, buf)
                        buf = io.StringIO()
                        batch_rows = 0
                if batch_rows > 0:
                    buf.seek(0)
                    pg_cur.copy_expert(copy_sql, buf)
            pg_conn.commit()
            loaded_names.append(table)
            if table_backfills:
                deferred_backfills.append((table, pk_cols, table_backfills))
            dupe_note = f" (skipped {skipped_dupes} duplicate row(s))" if skipped_dupes else ""
            print(f"[tier {spec['tier']}] {table}: copied {total_rows} rows{dupe_note}")

        # Backfill deferred mutual-FK columns once all tables are present
        for table, pk_cols, rows_to_update in deferred_backfills:
            with pg_conn.cursor() as pg_cur:
                where_clause = " AND ".join(f"{_quote_ident(c)} = %s" for c in pk_cols)
                for pk_tuple, col_map in rows_to_update:
                    set_clause = ", ".join(f"{_quote_ident(c)} = %s" for c in col_map)
                    params = list(col_map.values()) + list(pk_tuple)
                    pg_cur.execute(
                        f"UPDATE {_quote_ident(schema)}.{_quote_ident(table)} SET {set_clause} WHERE {where_clause}",
                        params,
                    )
            pg_conn.commit()
            print(f"[backfill] {table}: updated {len(rows_to_update)} deferred FK rows")

        seq_count = sync_sequences(pg_conn, schema, loaded_names)
        print(f"[sequences] synchronized {seq_count} sequences")

        if not skip_post_sql:
            for rel_path in manifest.get("post_load_sql", []):
                sql_path = REPO_ROOT / rel_path
                sql_text = sql_path.read_text(encoding="utf-8")
                with pg_conn.cursor() as pg_cur:
                    pg_cur.execute(sql_text)
                pg_conn.commit()
                print(f"[post-sql] applied {rel_path}")
    finally:
        my_conn.close()
        pg_conn.close()


def parse_args(argv: Optional[Sequence[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Stream legacy MariaDB data into Flyway PostgreSQL.")
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--tier", type=int, action="append", help="Tier(s) to load (default: 0,1,2,3)")
    parser.add_argument("--table", action="append", help="Explicit table(s) to load")
    parser.add_argument("--include-optional", action="store_true", help="Include optional tables")
    parser.add_argument("--chunk-size", type=int, default=5000, help="Rows per COPY batch")
    parser.add_argument("--dry-run", action="store_true", help="Print load plan without connecting")
    parser.add_argument("--skip-post-sql", action="store_true", help="Skip post_load_sql scripts")
    parser.add_argument(
        "--clear-seed",
        action="store_true",
        help="Clear planned target tables via reverse-order DELETE before COPY (for fresh Flyway V0002 seed rows)",
    )
    parser.add_argument(
        "--resume-from",
        help="Skip already-loaded tables before TABLE (retains deferred FK backfill, sequence sync, and post-SQL)",
    )
    return parser.parse_args(argv)


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = parse_args(argv)
    manifest = load_manifest(args.manifest)
    planned = plan_tables(
        manifest,
        tiers=args.tier,
        only_tables=args.table,
        include_optional=args.include_optional,
    )
    run_migration(
        manifest,
        planned,
        chunk_size=args.chunk_size,
        dry_run=args.dry_run,
        skip_post_sql=args.skip_post_sql,
        clear_seed=args.clear_seed,
        resume_from=args.resume_from,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
