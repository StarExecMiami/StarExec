#!/usr/bin/env python3
"""Unit tests verifying scripts/mysql2pg/manifest.yaml against Flyway migrations."""

from collections import defaultdict
import glob
import os
from pathlib import Path
import re
import unittest

import yaml

REPO_ROOT = Path(__file__).resolve().parents[2]
MANIFEST_PATH = REPO_ROOT / "scripts" / "mysql2pg" / "manifest.yaml"
MIGRATION_DIR = REPO_ROOT / "starexec-app" / "src" / "main" / "resources" / "db" / "migration"


def parse_flyway_schema(migration_dir: Path):
    """Extract table names and (child, column, parent) FK edges from Flyway V*.sql."""
    tables = set()
    # child -> list of (fk_column, parent_table)
    fk_edges = defaultdict(list)

    create_re = re.compile(
        r'CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(?:starexec\.)?`?"?([a-zA-Z0-9_]+)`?"?\s*\((.*?)\);',
        re.S | re.I,
    )
    inline_fk_re = re.compile(
        r'FOREIGN\s+KEY\s*\(\s*`?"?([a-zA-Z0-9_]+)`?"?\s*\)\s*REFERENCES\s+(?:starexec\.)?`?"?([a-zA-Z0-9_]+)`?"?\s*\(',
        re.I,
    )
    alter_fk_re = re.compile(
        r'ALTER\s+TABLE\s+(?:ONLY\s+)?(?:starexec\.)?`?"?([a-zA-Z0-9_]+)`?"?[^;]*?'
        r'FOREIGN\s+KEY\s*\(\s*`?"?([a-zA-Z0-9_]+)`?"?\s*\)\s*REFERENCES\s+(?:starexec\.)?`?"?([a-zA-Z0-9_]+)`?"?\s*\(',
        re.S | re.I,
    )

    for path in sorted(glob.glob(os.path.join(migration_dir, "V*.sql"))):
        sql = Path(path).read_text(encoding="utf-8")
        sql = re.sub(r"--[^\n]*", "", sql)
        for m in create_re.finditer(sql):
            tbl = m.group(1).lower()
            body = m.group(2)
            tables.add(tbl)
            for fm in inline_fk_re.finditer(body):
                col = fm.group(1).lower()
                parent = fm.group(2).lower()
                if parent != tbl:
                    fk_edges[tbl].append((col, parent))
        for m in alter_fk_re.finditer(sql):
            child = m.group(1).lower()
            col = m.group(2).lower()
            parent = m.group(3).lower()
            if parent != child:
                fk_edges[child].append((col, parent))

    return tables, fk_edges


class ManifestValidationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest = yaml.safe_load(MANIFEST_PATH.read_text(encoding="utf-8"))
        cls.flyway_tables, cls.fk_edges = parse_flyway_schema(MIGRATION_DIR)

    def test_required_top_level_fields(self):
        self.assertEqual(self.manifest["schema"], "starexec")
        self.assertEqual(self.manifest["source_timezone"], "+00:00")
        self.assertIn("tiers", self.manifest)
        self.assertEqual(sorted(self.manifest["tiers"].keys()), [0, 1, 2, 3, 4])

    def test_no_duplicate_tables_and_all_exist_in_flyway(self):
        seen = set()
        for tier_num, tier in self.manifest["tiers"].items():
            for entry in tier["tables"]:
                name = entry["name"]
                self.assertNotIn(name, seen, f"Duplicate table {name} in tier {tier_num}")
                seen.add(name)
                self.assertIn(
                    name,
                    self.flyway_tables,
                    f"Manifest table '{name}' (tier {tier_num}) not found in Flyway schema",
                )
        self.assertEqual(len(seen), 61, "Expected all 61 legacy MariaDB base tables")

    def test_loaded_tables_respect_fk_topological_order(self):
        load_position = {}
        deferred = {}
        pos = 0
        for tier_num in (0, 1, 2, 3):
            for entry in self.manifest["tiers"][tier_num]["tables"]:
                name = entry["name"]
                load_position[name] = pos
                deferred[name] = set(entry.get("deferred_fk_columns", []))
                pos += 1

        violations = []
        for child, child_pos in load_position.items():
            for fk_col, parent in self.fk_edges.get(child, []):
                if fk_col in deferred[child]:
                    continue
                if parent not in load_position:
                    # Target-only Flyway tables (e.g. solver_build_artifacts) are
                    # empty during legacy import; legacy rows carry NULL for them.
                    continue
                if load_position[parent] >= child_pos:
                    violations.append(
                        f"{child} (pos {child_pos}) references {parent} (pos {load_position[parent]}) via {fk_col}"
                    )

        self.assertEqual(violations, [], "FK load-order violations:\n" + "\n".join(violations))

    def test_system_flags_starts_paused(self):
        tier0 = {e["name"]: e for e in self.manifest["tiers"][0]["tables"]}
        self.assertTrue(tier0["system_flags"]["overrides"]["paused"])

    def test_post_load_sql_files_exist(self):
        self.assertTrue(self.manifest["post_load_sql"])
        for rel_path in self.manifest["post_load_sql"]:
            self.assertTrue(
                (REPO_ROOT / rel_path).is_file(),
                f"Missing post_load_sql script: {rel_path}",
            )


if __name__ == "__main__":
    unittest.main()
