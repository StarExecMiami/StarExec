"""Tests for npm_audit_gate.py. Run: python3 -m unittest discover -s .github/scripts -p 'test_*.py'"""

import datetime
import unittest

import npm_audit_gate as gate

TODAY = datetime.date(2026, 10, 15)
EXCEPTIONS = [
    {
        "id": "GHSA-vfj7-8cjw-p6xm",
        "package": "braces",
        "reason": "build tooling only",
        "review_by": "2026-12-31",
    }
]


def advisory(name, ghsa, severity="high"):
    return {
        "name": name,
        "severity": severity,
        "title": "a problem",
        "url": "https://github.com/advisories/" + ghsa,
    }


def audit(vulnerabilities):
    return {"auditReportVersion": 2, "vulnerabilities": vulnerabilities}


def braces(fix=False, nodes=("node_modules/braces",)):
    return {
        "braces": {
            "name": "braces",
            "severity": "high",
            "via": [advisory("braces", "GHSA-vfj7-8cjw-p6xm")],
            "nodes": list(nodes),
            "fixAvailable": fix,
        },
        # Inherits it; npm lists the advisory by name only, on the package that owns it.
        "micromatch": {
            "name": "micromatch",
            "severity": "high",
            "via": ["braces"],
            "nodes": ["node_modules/micromatch"],
            "fixAvailable": fix,
        },
    }


def lock(dev_braces=True):
    return {
        "packages": {
            "": {"name": "starexec"},
            "node_modules/braces": {"version": "3.0.3", "dev": dev_braces},
            "node_modules/micromatch": {"version": "4.0.8", "dev": True},
            "node_modules/jquery": {"version": "3.7.1", "dev": True},
        }
    }


class GateTest(unittest.TestCase):
    def evaluate(
        self, vulnerabilities, lock_data=None, exceptions=EXCEPTIONS, today=TODAY
    ):
        return gate.evaluate(
            audit(vulnerabilities), lock_data or lock(), exceptions, today
        )

    def test_the_excepted_build_tooling_advisory_passes(self):
        failures, excepted, notes = self.evaluate(braces())
        self.assertEqual([], failures)
        self.assertEqual(1, len(excepted))
        self.assertIn("GHSA-vfj7-8cjw-p6xm", excepted[0])
        self.assertEqual([], notes)

    def test_any_other_high_advisory_fails(self):
        vulns = braces()
        vulns["jquery"] = {
            "name": "jquery",
            "severity": "high",
            "via": [advisory("jquery", "GHSA-aaaa-bbbb-cccc")],
            "nodes": ["node_modules/jquery"],
            "fixAvailable": True,
        }
        failures, _, _ = self.evaluate(vulns)
        self.assertEqual(1, len(failures))
        self.assertIn("jquery GHSA-aaaa-bbbb-cccc", failures[0])

    def test_a_critical_advisory_fails(self):
        failures, _, _ = self.evaluate(
            {
                "x": {
                    "name": "x",
                    "severity": "critical",
                    "via": [advisory("x", "GHSA-xxxx-yyyy-zzzz", "critical")],
                    "nodes": ["node_modules/x"],
                    "fixAvailable": False,
                }
            }
        )
        self.assertEqual(1, len(failures))

    def test_moderate_advisories_do_not_fail_as_before(self):
        failures, excepted, _ = self.evaluate(
            {
                "y": {
                    "name": "y",
                    "severity": "moderate",
                    "via": [advisory("y", "GHSA-mmmm-nnnn-pppp", "moderate")],
                    "nodes": ["node_modules/y"],
                    "fixAvailable": False,
                }
            }
        )
        self.assertEqual(([], []), (failures, excepted))

    def test_the_exception_ends_when_the_package_would_ship(self):
        failures, _, _ = self.evaluate(braces(), lock_data=lock(dev_braces=False))
        self.assertEqual(1, len(failures))
        self.assertIn("shipped", failures[0])

    def test_the_exception_ends_when_a_fix_appears(self):
        failures, _, _ = self.evaluate(braces(fix=True))
        self.assertEqual(1, len(failures))
        self.assertIn("upgrade", failures[0])

    def test_the_exception_ends_on_its_review_date(self):
        failures, _, _ = self.evaluate(braces(), today=datetime.date(2027, 1, 1))
        self.assertEqual(1, len(failures))
        self.assertIn("expired", failures[0])

    def test_an_exception_for_another_package_does_not_apply(self):
        other = [dict(EXCEPTIONS[0], package="micromatch")]
        failures, _, notes = self.evaluate(braces(), exceptions=other)
        self.assertEqual(1, len(failures))
        self.assertEqual(1, len(notes))

    def test_a_failed_audit_never_passes(self):
        failures, _, _ = gate.evaluate(
            {"error": {"code": "ENOAUDIT", "summary": "network"}},
            lock(),
            EXCEPTIONS,
            TODAY,
        )
        self.assertEqual(1, len(failures))


if __name__ == "__main__":
    unittest.main()
