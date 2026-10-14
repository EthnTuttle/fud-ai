#!/usr/bin/env python3
"""Offline tests for check_ios_test_counts.py (the zero-test guard in test-ios.sh).

The fixture has the shape `xcrun xcresulttool get test-results tests` prints for
Fud AI's result bundle with Xcode 27. Run:
    python3 -m unittest scripts/ci/test_check_ios_test_counts.py
"""
from __future__ import annotations

import io
import json
import subprocess
import sys
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_ios_test_counts as counts  # noqa: E402

BUNDLES = ["calorietrackerTests", "calorietrackerUITests"]


def case(name: str, result: str = "Passed") -> dict:
    return {"nodeType": "Test Case", "name": name, "result": result, "children": []}


def suite(name: str, cases: list[dict]) -> dict:
    return {"nodeType": "Test Suite", "name": name, "children": cases}


def results(unit_cases: list[dict], ui_cases: list[dict] | None) -> dict:
    bundles = [
        {"nodeType": "Unit test bundle", "name": "calorietrackerTests",
         "children": [suite("IngredientPortionTests", unit_cases)] if unit_cases else []},
    ]
    if ui_cases is not None:
        bundles.append({"nodeType": "UI test bundle", "name": "calorietrackerUITests",
                        "children": [suite("calorietrackerUITests", ui_cases)] if ui_cases else []})
    return {"devices": [], "testPlanConfigurations": [],
            "testNodes": [{"nodeType": "Test Plan", "name": "calorietracker", "children": bundles}]}


class CountTests(unittest.TestCase):
    def test_counts_cases_per_bundle(self) -> None:
        found, outcomes = counts.bundle_counts(
            results([case("a()"), case("b()", "Failed")], [case("testLaunch()")])
        )
        self.assertEqual(found["calorietrackerTests"], 2)
        self.assertEqual(found["calorietrackerUITests"], 1)
        self.assertEqual(outcomes[("calorietrackerTests", "Failed")], 1)
        self.assertEqual(counts.problems(found, BUNDLES), [])

    def test_bundle_that_ran_nothing_or_did_not_run_fails(self) -> None:
        found, _ = counts.bundle_counts(results([case("a()")], []))
        self.assertEqual(counts.problems(found, BUNDLES), ["calorietrackerUITests ran zero tests"])
        found, _ = counts.bundle_counts(results([case("a()")], None))
        self.assertEqual(counts.problems(found, BUNDLES), ["calorietrackerUITests did not run"])
        found, _ = counts.bundle_counts({"testNodes": []})
        self.assertEqual(len(counts.problems(found, BUNDLES)), 2)


class CliTests(unittest.TestCase):
    def run_main(self, payload: dict) -> tuple[int, str]:
        completed = subprocess.CompletedProcess([], 0, stdout=json.dumps(payload), stderr="")
        out = io.StringIO()
        with mock.patch.object(counts.subprocess, "run", return_value=completed) as run, \
                redirect_stdout(out), redirect_stderr(out):
            code = counts.main(["check_ios_test_counts.py", "Tests.xcresult", *BUNDLES])
        self.assertEqual(run.call_args[0][0][:4], ["xcrun", "xcresulttool", "get", "test-results"])
        return code, out.getvalue()

    def test_exit_codes(self) -> None:
        code, output = self.run_main(results([case("a()")], [case("testLaunch()")]))
        self.assertEqual(code, 0, output)
        self.assertIn("calorietrackerUITests: 1 test(s) (passed 1)", output)
        code, output = self.run_main(results([case("a()")], []))
        self.assertEqual(code, 1)
        self.assertIn("error: calorietrackerUITests ran zero tests", output)

    def test_usage_without_bundles(self) -> None:
        with redirect_stderr(io.StringIO()):
            self.assertEqual(counts.main(["check_ios_test_counts.py", "Tests.xcresult"]), 2)


if __name__ == "__main__":
    unittest.main()
