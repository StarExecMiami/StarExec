#!/usr/bin/env python3
"""Unit tests for scripts/mysql2pg/validate.py (no live DB required)."""

import unittest

from scripts.mysql2pg import validate


class ValidateUnitTest(unittest.TestCase):
    def test_count_parity_passes_when_equal(self):
        errs = validate.evaluate_count_parity(
            {"users": 83, "jobs": 985},
            {"users": 83, "jobs": 985},
        )
        self.assertEqual(errs, [])

    def test_count_parity_reports_mismatch_and_missing(self):
        errs = validate.evaluate_count_parity(
            {"users": 83},
            {"users": 82, "jobs": 10},
        )
        self.assertEqual(len(errs), 2)
        self.assertTrue(any("users" in e and "83" in e and "82" in e for e in errs))
        self.assertTrue(any("jobs" in e and "missing source count" in e for e in errs))

    def test_sequence_bounds_checks_max_id_and_is_called(self):
        ok_states = [
            ("users", "starexec.users_id_seq", 83, 83, True),
            ("empty_tbl", "starexec.empty_tbl_id_seq", 0, 1, False),
        ]
        self.assertEqual(validate.evaluate_sequence_bounds(ok_states), [])

        bad_states = [
            ("users", "starexec.users_id_seq", 83, 82, True),
            ("jobs", "starexec.jobs_id_seq", 10, 10, False),
        ]
        errs = validate.evaluate_sequence_bounds(bad_states)
        self.assertEqual(len(errs), 2)

    def test_mysql_count_sql_handles_dedupe_columns(self):
        plain = validate.mysql_count_sql("starexec", "benchmarks")
        self.assertEqual(plain, "SELECT COUNT(*) FROM `starexec`.`benchmarks`")
        deduped = validate.mysql_count_sql(
            "starexec", "bench_dependency", ["primary_bench_id", "secondary_bench_id"]
        )
        self.assertEqual(
            deduped,
            "SELECT COUNT(*) FROM (SELECT DISTINCT `primary_bench_id`, `secondary_bench_id` FROM `starexec`.`bench_dependency`) AS _dedup",
        )


if __name__ == "__main__":
    unittest.main()
