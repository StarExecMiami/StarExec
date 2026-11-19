#!/usr/bin/env python3
"""Unit tests for scripts/mysql2pg/migrate.py (no live DB required)."""

import datetime
import io
from contextlib import redirect_stdout
import unittest

from scripts.mysql2pg import migrate


class MigrateUnitTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest = migrate.load_manifest(migrate.DEFAULT_MANIFEST)

    def test_default_plan_excludes_tier4_and_optional_tables(self):
        planned = migrate.plan_tables(self.manifest)
        names = {t["name"] for t in planned}
        self.assertIn("users", names)
        self.assertIn("system_flags", names)
        self.assertIn("job_pairs", names)
        # Tier 4 drop tables must not be in default plan
        self.assertNotIn("comm_queue", names)
        self.assertNotIn("analytics_events", names)
        # Optional tables excluded unless --include-optional
        self.assertNotIn("report_data", names)
        self.assertNotIn("space_xml_uploads", names)

    def test_include_optional_adds_optional_tables_but_still_drops_tier4_drop(self):
        planned = migrate.plan_tables(self.manifest, include_optional=True)
        names = {t["name"] for t in planned}
        self.assertIn("report_data", names)
        self.assertIn("space_xml_uploads", names)
        self.assertNotIn("comm_queue", names)

    def test_unknown_explicit_table_raises(self):
        with self.assertRaises(ValueError):
            migrate.plan_tables(self.manifest, only_tables=["nonexistent_table"])

    def test_transform_row_booleans_zero_dates_overrides_and_deferred_fks(self):
        cols = ["id", "paused", "completed", "primary_stage_id", "active_byte"]
        pg_types = {
            "id": "integer",
            "paused": "boolean",
            "completed": "timestamp without time zone",
            "primary_stage_id": "integer",
            "active_byte": "boolean",
        }
        row = (42, 0, "0000-00-00 00:00:00", 99, b"\x01")
        out, deferred = migrate.transform_row(
            row,
            columns=cols,
            pg_col_types=pg_types,
            zero_date_cols={"completed"},
            overrides={"paused": True},
            deferred_fk_cols={"primary_stage_id"},
        )
        self.assertEqual(out, [42, True, None, None, True])
        self.assertEqual(deferred, {"primary_stage_id": 99})

    def test_format_copy_line_escapes_special_chars_and_nulls(self):
        line = migrate.format_copy_line(
            [
                1,
                None,
                True,
                False,
                "a\tb\nc\\d\re\x00f",
                datetime.datetime(2026, 11, 19, 7, 15, 0),
            ]
        )
        self.assertEqual(
            line,
            "1\t\\N\tt\tf\ta\\tb\\nc\\\\d\\ref\t2026-11-19 07:15:00\n",
        )

    def test_cli_dry_run_succeeds_without_db_credentials(self):
        buf = io.StringIO()
        with redirect_stdout(buf):
            rc = migrate.main(["--dry-run", "--tier", "0"])
        self.assertEqual(rc, 0)
        output = buf.getvalue()
        self.assertIn("[DRY-RUN] Target schema: starexec", output)
        self.assertIn("[tier 0] system_flags", output)
        self.assertIn("sql/wrap-legacy-password-hashes.sql", output)


if __name__ == "__main__":
    unittest.main()
