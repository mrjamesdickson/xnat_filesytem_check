#!/usr/bin/env python3
"""
XNAT Filesystem Check Plugin
Copyright (c) 2025 XNATWorks.
All rights reserved.

Integration tests for xnat_fs_check.py using real XNAT projects as source of truth.

These tests require a running XNAT instance. Configure via environment variables:
    XNAT_TEST_URL      - XNAT base URL (default: http://demo02.xnatworks.io)
    XNAT_TEST_USER     - XNAT username (default: admin)
    XNAT_TEST_PASSWORD - XNAT password (default: admin)

Run:
    python3 -m pytest scripts/test_xnat_fs_check.py -v
"""

from __future__ import annotations

import os
import json
import tempfile
import unittest
from pathlib import Path
from typing import Dict, List, Optional
from unittest.mock import patch

import requests

from xnat_fs_check import (
    XNATClient,
    FilesystemChecker,
    CheckReport,
    _extract_results,
    _first_value,
    build_parser,
    main,
)


# ---------------------------------------------------------------------------
# Test configuration from environment or sensible defaults
# ---------------------------------------------------------------------------

XNAT_URL = os.environ.get("XNAT_TEST_URL", "http://demo02.xnatworks.io")
XNAT_USER = os.environ.get("XNAT_TEST_USER", "admin")
XNAT_PASSWORD = os.environ.get("XNAT_TEST_PASSWORD", "admin")


def _xnat_reachable() -> bool:
    """Check if XNAT instance is reachable before running integration tests."""
    try:
        resp = requests.get(
            f"{XNAT_URL}/data/archive/projects",
            params={"format": "json"},
            auth=(XNAT_USER, XNAT_PASSWORD),
            timeout=10,
        )
        return resp.status_code == 200
    except requests.RequestException:
        return False


XNAT_AVAILABLE = _xnat_reachable()


# ===================================================================
# Unit tests (no XNAT connection needed)
# ===================================================================


class TestExtractResults(unittest.TestCase):
    """Test _extract_results handles various XNAT JSON shapes."""

    def test_standard_result_set(self):
        payload = {"ResultSet": {"Result": [{"ID": "1"}, {"ID": "2"}]}}
        results = _extract_results(payload)
        self.assertEqual(len(results), 2)
        self.assertEqual(results[0]["ID"], "1")

    def test_lowercase_result_set(self):
        payload = {"resultSet": {"result": [{"id": "a"}]}}
        results = _extract_results(payload)
        self.assertEqual(len(results), 1)

    def test_top_level_result(self):
        payload = {"Result": [{"ID": "x"}]}
        results = _extract_results(payload)
        self.assertEqual(len(results), 1)

    def test_non_dict_returns_empty(self):
        self.assertEqual(_extract_results([1, 2, 3]), [])
        self.assertEqual(_extract_results("string"), [])

    def test_empty_dict_returns_empty(self):
        self.assertEqual(_extract_results({}), [])

    def test_result_set_without_result_key(self):
        payload = {"ResultSet": {"totalRecords": 0}}
        self.assertEqual(_extract_results(payload), [])


class TestFirstValue(unittest.TestCase):
    """Test _first_value helper."""

    def test_returns_first_match(self):
        row = {"name": "foo", "ID": "bar"}
        self.assertEqual(_first_value(row, ("label", "name", "ID")), "foo")

    def test_skips_none_and_empty(self):
        row = {"label": None, "name": "", "ID": "found"}
        self.assertEqual(_first_value(row, ("label", "name", "ID")), "found")

    def test_returns_none_when_no_match(self):
        self.assertIsNone(_first_value({}, ("label", "name")))

    def test_converts_to_string(self):
        row = {"count": 42}
        self.assertEqual(_first_value(row, ("count",)), "42")


class TestCheckReport(unittest.TestCase):
    """Test CheckReport serialisation methods."""

    def _make_report(self, **kwargs) -> CheckReport:
        stats = {
            "projects": 1, "sessions": 2, "scans": 3, "assessors": 0,
            "resources": 4, "session_resources": 1, "scan_resources": 3,
            "assessor_resources": 0, "files_total": 10, "files_found": 8,
            "files_missing": 1, "files_unresolved": 1,
            "catalogs_total": 0, "catalog_entries_total": 0,
            "catalog_entries_missing": 0, "catalogs_with_errors": 0,
        }
        defaults = dict(stats=stats, missing_files=[], unresolved_files=[],
                        resource_details=[], project_summaries={})
        defaults.update(kwargs)
        return CheckReport(**defaults)

    def test_to_dict_has_required_keys(self):
        report = self._make_report()
        d = report.to_dict()
        for key in ("generated_at", "stats", "missing_files", "unresolved_files",
                     "resource_details", "project_summaries"):
            self.assertIn(key, d)

    def test_to_json_roundtrip(self):
        report = self._make_report()
        serialised = json.dumps(report.to_dict())
        loaded = json.loads(serialised)
        self.assertEqual(loaded["stats"]["files_total"], 10)

    def test_to_text_includes_stats(self):
        report = self._make_report()
        text = report.to_text()
        self.assertIn("Files checked: 10", text)
        self.assertIn("Missing: 1", text)

    def test_to_html_contains_report_title(self):
        report = self._make_report()
        html = report.to_html()
        self.assertIn("XNAT Filesystem Check Report", html)
        self.assertIn("Files Missing", html)

    def test_to_html_missing_files_section(self):
        report = self._make_report(missing_files=[{
            "project": "P1", "session": "S1", "resource": "R1",
            "scope": "scan", "scan": "1", "path": "/missing/file.dcm",
        }])
        html = report.to_html()
        self.assertIn("Missing Files", html)
        self.assertIn("/missing/file.dcm", html)

    def test_project_summary_in_text(self):
        report = self._make_report(project_summaries={
            "ProjectA": {"sessions": 5, "files_total": 100,
                         "files_found": 99, "files_missing": 1, "files_unresolved": 0},
        })
        text = report.to_text()
        self.assertIn("ProjectA", text)
        self.assertIn("missing=1", text)


class TestXNATClientInit(unittest.TestCase):
    """Test XNATClient construction validation."""

    def test_empty_base_url_raises(self):
        with self.assertRaises(ValueError):
            XNATClient("")

    def test_username_without_password_raises(self):
        with self.assertRaises(ValueError):
            XNATClient("http://example.com", username="user")

    def test_token_auth_sets_header(self):
        client = XNATClient("http://example.com", token="my-token")
        self.assertEqual(
            client.session.headers.get("Authorization"), "Bearer my-token"
        )
        client.close()

    def test_basic_auth_sets_credentials(self):
        client = XNATClient("http://example.com", username="u", password="p")
        self.assertEqual(client.session.auth, ("u", "p"))
        client.close()

    def test_trailing_slash_stripped(self):
        client = XNATClient("http://example.com/")
        self.assertEqual(client.base_url, "http://example.com")
        client.close()


class TestBuildParser(unittest.TestCase):
    """Test argument parser construction."""

    def test_base_url_required(self):
        parser = build_parser()
        with self.assertRaises(SystemExit):
            parser.parse_args([])

    def test_defaults(self):
        parser = build_parser()
        args = parser.parse_args(["--base-url", "http://example.com"])
        self.assertEqual(args.timeout, 30.0)
        self.assertTrue(args.verify_ssl)
        self.assertEqual(args.log_level, "INFO")
        self.assertFalse(args.fail_on_missing)

    def test_project_accumulates(self):
        parser = build_parser()
        args = parser.parse_args([
            "--base-url", "http://x", "--project", "A", "--project", "B"
        ])
        self.assertEqual(args.include_projects, ["A", "B"])


class TestIterResourceFilesURLConstruction(unittest.TestCase):
    """Test that iter_resource_files builds correct URLs for each scope."""

    def _capture_url(self, client: XNATClient, **kwargs) -> str:
        """Call iter_resource_files with a resource row that has no URI and
        capture the URL that would be requested."""
        captured = {}

        original_request_json = client._request_json

        def mock_request(path_or_url, params=None):
            captured["url"] = path_or_url
            return {"ResultSet": {"Result": []}}

        client._request_json = mock_request
        try:
            list(client.iter_resource_files({"label": "DICOM"}, **kwargs))
        finally:
            client._request_json = original_request_json
        return captured.get("url", "")

    def test_session_scope_uses_experiment_uri(self):
        client = XNATClient("http://example.com", token="t")
        url = self._capture_url(
            client,
            experiment_uri="/data/experiments/XNAT_E00001",
            resource_label="DICOM",
        )
        self.assertEqual(url, "/data/experiments/XNAT_E00001/resources/DICOM/files")
        client.close()

    def test_scan_scope_includes_scan_id(self):
        client = XNATClient("http://example.com", token="t")
        url = self._capture_url(
            client,
            experiment_uri="/data/experiments/XNAT_E00001",
            resource_label="DICOM",
            scan_id="2",
        )
        self.assertEqual(
            url, "/data/experiments/XNAT_E00001/scans/2/resources/DICOM/files"
        )
        client.close()

    def test_assessor_scope_includes_assessor_id(self):
        client = XNATClient("http://example.com", token="t")
        url = self._capture_url(
            client,
            experiment_uri="/data/experiments/XNAT_E00001",
            resource_label="RTSTRUCT",
            assessor_id="XNAT_E00002",
        )
        self.assertEqual(
            url,
            "/data/experiments/XNAT_E00001/assessors/XNAT_E00002/resources/RTSTRUCT/files",
        )
        client.close()

    def test_resource_uri_takes_precedence(self):
        """When the resource row has a URI, it should be used directly."""
        client = XNATClient("http://example.com", token="t")
        captured = {}

        def mock_request(path_or_url, params=None):
            captured["url"] = path_or_url
            return {"ResultSet": {"Result": []}}

        client._request_json = mock_request
        resource_row = {"URI": "/data/experiments/XNAT_E00001/scans/5/resources/NIFTI"}
        list(client.iter_resource_files(
            resource_row,
            experiment_uri="/data/experiments/XNAT_E00001",
            resource_label="NIFTI",
            scan_id="5",
        ))
        self.assertEqual(
            captured["url"],
            "/data/experiments/XNAT_E00001/scans/5/resources/NIFTI/files",
        )
        client.close()

    def test_fallback_to_resource_id(self):
        """When no experiment_uri or resource_label, falls back to resource ID."""
        client = XNATClient("http://example.com", token="t")
        url = self._capture_url(client)
        self.assertEqual(url, "/data/archive/resources/DICOM/files")
        client.close()

    def test_special_characters_quoted(self):
        client = XNATClient("http://example.com", token="t")
        url = self._capture_url(
            client,
            experiment_uri="/data/experiments/XNAT_E00001",
            resource_label="My Resource",
            scan_id="scan 1",
        )
        self.assertIn("scan%201", url)
        self.assertIn("My%20Resource", url)
        client.close()


# ===================================================================
# Integration tests (require live XNAT)
# ===================================================================


@unittest.skipUnless(XNAT_AVAILABLE, f"XNAT not reachable at {XNAT_URL}")
class TestXNATClientIntegration(unittest.TestCase):
    """Integration tests using real XNAT projects as source of truth."""

    @classmethod
    def setUpClass(cls):
        cls.client = XNATClient(
            XNAT_URL, username=XNAT_USER, password=XNAT_PASSWORD, timeout=30
        )

    @classmethod
    def tearDownClass(cls):
        cls.client.close()

    def test_iter_projects_returns_results(self):
        projects = list(self.client.iter_projects())
        self.assertGreater(len(projects), 0, "XNAT should have at least one project")

    def test_project_row_has_id(self):
        projects = list(self.client.iter_projects())
        first = projects[0]
        project_id = _first_value(first, ("ID", "id", "label", "name"))
        self.assertIsNotNone(project_id)

    def test_iter_experiments_for_known_project(self):
        """Prostate-AEC should have experiments on demo02."""
        projects = list(self.client.iter_projects())
        prostate = next(
            (p for p in projects if p.get("ID") == "Prostate-AEC"), None
        )
        if prostate is None:
            self.skipTest("Prostate-AEC project not found on server")
        experiments = list(self.client.iter_project_experiments(prostate))
        self.assertGreater(len(experiments), 0)

    def test_experiment_row_has_uri(self):
        """Experiment rows should include a URI field for reliable URL building."""
        projects = list(self.client.iter_projects())
        for project in projects:
            experiments = list(self.client.iter_project_experiments(project))
            if experiments:
                first_exp = experiments[0]
                uri = first_exp.get("URI") or first_exp.get("uri")
                self.assertIsNotNone(uri, "Experiment row should have a URI")
                self.assertTrue(
                    uri.startswith("/data/experiments/"),
                    f"URI should start with /data/experiments/, got: {uri}",
                )
                return
        self.skipTest("No projects with experiments found")


@unittest.skipUnless(XNAT_AVAILABLE, f"XNAT not reachable at {XNAT_URL}")
class TestScanResourceFilesIntegration(unittest.TestCase):
    """Verify that scan-scoped resource file listing works with real data.

    This is the exact scenario that caused the original 404 bug: when a
    scan resource row has no URI field, the fallback URL must include the
    scan ID in the path.
    """

    @classmethod
    def setUpClass(cls):
        cls.client = XNATClient(
            XNAT_URL, username=XNAT_USER, password=XNAT_PASSWORD, timeout=30
        )
        cls.experiment_uri = None
        cls.scan_id = None
        cls.resource_label = None
        cls.resource_row_without_uri = None

        # Find a real scan resource to test against
        for project in cls.client.iter_projects():
            if project.get("ID") != "Prostate-AEC":
                continue
            for exp in cls.client.iter_project_experiments(project):
                cls.experiment_uri = exp.get("URI") or exp.get("uri")
                for scan in cls.client.iter_experiment_scans(exp):
                    cls.scan_id = _first_value(
                        scan, ("ID", "id", "scan_id")
                    )
                    for res in cls.client.iter_scan_resources(exp, scan):
                        cls.resource_label = _first_value(
                            res, ("label", "name", "resource_label")
                        )
                        # Simulate missing URI (the bug scenario)
                        cls.resource_row_without_uri = {
                            k: v for k, v in res.items() if k.lower() != "uri"
                        }
                        return
        # If we get here, we didn't find suitable data
        cls.experiment_uri = None

    @classmethod
    def tearDownClass(cls):
        cls.client.close()

    def test_scan_resource_files_with_experiment_uri(self):
        """File listing via experiment_uri + scan_id should return files."""
        if self.experiment_uri is None:
            self.skipTest("No scan resources found on server")

        files = list(self.client.iter_resource_files(
            self.resource_row_without_uri,
            experiment_uri=self.experiment_uri,
            resource_label=self.resource_label,
            scan_id=self.scan_id,
        ))
        self.assertGreater(
            len(files), 0,
            f"Expected files for {self.experiment_uri}/scans/{self.scan_id}"
            f"/resources/{self.resource_label}",
        )

    def test_scan_resource_files_without_scan_id_would_fail(self):
        """Without scan_id, the URL points to session-level which has no DICOM resource."""
        if self.experiment_uri is None:
            self.skipTest("No scan resources found on server")

        # Session-level listing for this resource label - may 404 or return
        # different files. Either way, it's wrong for scan-scoped resources.
        try:
            session_files = list(self.client.iter_resource_files(
                self.resource_row_without_uri,
                experiment_uri=self.experiment_uri,
                resource_label=self.resource_label,
                # scan_id intentionally omitted
            ))
        except requests.HTTPError:
            # 404 is the expected failure mode (the original bug)
            return

        # If it didn't 404, the file count should differ from scan-level
        scan_files = list(self.client.iter_resource_files(
            self.resource_row_without_uri,
            experiment_uri=self.experiment_uri,
            resource_label=self.resource_label,
            scan_id=self.scan_id,
        ))
        # At minimum, document that the paths are different
        self.assertNotEqual(
            len(session_files), len(scan_files),
            "Session-scoped and scan-scoped file counts should differ "
            "(or session-scoped should 404)",
        )


@unittest.skipUnless(XNAT_AVAILABLE, f"XNAT not reachable at {XNAT_URL}")
class TestAssessorResourceFilesIntegration(unittest.TestCase):
    """Verify assessor-scoped resource file listing works with real data."""

    @classmethod
    def setUpClass(cls):
        cls.client = XNATClient(
            XNAT_URL, username=XNAT_USER, password=XNAT_PASSWORD, timeout=30
        )
        cls.experiment_uri = None
        cls.assessor_id = None
        cls.resource_label = None
        cls.resource_row_without_uri = None

        for project in cls.client.iter_projects():
            if project.get("ID") != "Prostate-AEC":
                continue
            for exp in cls.client.iter_project_experiments(project):
                cls.experiment_uri = exp.get("URI") or exp.get("uri")
                try:
                    assessors = list(cls.client.iter_experiment_assessors(exp))
                except requests.RequestException:
                    continue
                for assessor in assessors:
                    cls.assessor_id = _first_value(
                        assessor, ("ID", "id", "label")
                    )
                    try:
                        resources = list(
                            cls.client.iter_assessor_resources(exp, assessor)
                        )
                    except requests.RequestException:
                        continue
                    for res in resources:
                        cls.resource_label = _first_value(
                            res, ("label", "name", "resource_label")
                        )
                        cls.resource_row_without_uri = {
                            k: v for k, v in res.items() if k.lower() != "uri"
                        }
                        return
        cls.experiment_uri = None

    @classmethod
    def tearDownClass(cls):
        cls.client.close()

    def test_assessor_resource_files_with_experiment_uri(self):
        """File listing via experiment_uri + assessor_id should return files."""
        if self.experiment_uri is None:
            self.skipTest("No assessor resources found on server")

        files = list(self.client.iter_resource_files(
            self.resource_row_without_uri,
            experiment_uri=self.experiment_uri,
            resource_label=self.resource_label,
            assessor_id=self.assessor_id,
        ))
        self.assertGreater(
            len(files), 0,
            f"Expected files for {self.experiment_uri}/assessors/{self.assessor_id}"
            f"/resources/{self.resource_label}",
        )


@unittest.skipUnless(XNAT_AVAILABLE, f"XNAT not reachable at {XNAT_URL}")
class TestFilesystemCheckerIntegration(unittest.TestCase):
    """End-to-end test of FilesystemChecker.check() against real XNAT data."""

    def test_check_single_project_with_max_files(self):
        """Run a bounded check against a known project."""
        client = XNATClient(
            XNAT_URL, username=XNAT_USER, password=XNAT_PASSWORD, timeout=30
        )
        checker = FilesystemChecker(client, data_root=None, max_files=10)
        report = checker.check(include_projects=["Prostate-AEC"])

        stats = report.stats
        self.assertGreaterEqual(stats["projects"], 1)
        self.assertGreaterEqual(stats["sessions"], 1)
        self.assertGreaterEqual(stats["files_total"], 1)
        # Without data_root, all files should be unresolved (no filesystem access)
        self.assertEqual(stats["files_found"], 0)
        self.assertEqual(stats["files_missing"], 0)

    def test_check_produces_project_summary(self):
        """Per-project summaries should be populated."""
        client = XNATClient(
            XNAT_URL, username=XNAT_USER, password=XNAT_PASSWORD, timeout=30
        )
        checker = FilesystemChecker(client, data_root=None, max_files=5)
        report = checker.check(include_projects=["Prostate-AEC"])

        self.assertIn("Prostate-AEC", report.project_summaries)
        pstats = report.project_summaries["Prostate-AEC"]
        self.assertGreaterEqual(pstats["sessions"], 1)

    def test_check_skip_project(self):
        """Skipped projects should not appear in results."""
        client = XNATClient(
            XNAT_URL, username=XNAT_USER, password=XNAT_PASSWORD, timeout=30
        )
        checker = FilesystemChecker(client, data_root=None, max_files=5)
        report = checker.check(skip_projects=["Prostate-AEC"])

        self.assertNotIn("Prostate-AEC", report.project_summaries)

    def test_check_resource_details_populated(self):
        """Resource detail records should be created during check."""
        client = XNATClient(
            XNAT_URL, username=XNAT_USER, password=XNAT_PASSWORD, timeout=30
        )
        checker = FilesystemChecker(client, data_root=None, max_files=5)
        report = checker.check(include_projects=["Prostate-AEC"])

        self.assertGreater(len(report.resource_details), 0)
        first = report.resource_details[0]
        self.assertIn("project", first)
        self.assertIn("session", first)
        self.assertIn("resource", first)
        self.assertIn("status", first)


@unittest.skipUnless(XNAT_AVAILABLE, f"XNAT not reachable at {XNAT_URL}")
class TestMainIntegration(unittest.TestCase):
    """Test main() CLI entrypoint against real XNAT."""

    def test_main_produces_reports(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            json_path = os.path.join(tmpdir, "report.json")
            html_path = os.path.join(tmpdir, "report.html")
            csv_path = os.path.join(tmpdir, "resources.csv")
            exit_code = main([
                "--base-url", XNAT_URL,
                "--username", XNAT_USER,
                "--password", XNAT_PASSWORD,
                "--project", "Prostate-AEC",
                "--max-files", "5",
                "--report", f"json:{json_path}",
                "--report", f"html:{html_path}",
                "--resource-report-file", csv_path,
                "--resource-report-format", "csv",
            ])
            self.assertEqual(exit_code, 0)
            self.assertTrue(os.path.exists(json_path))
            self.assertTrue(os.path.exists(html_path))
            self.assertTrue(os.path.exists(csv_path))

            with open(json_path) as f:
                data = json.load(f)
            self.assertIn("stats", data)
            self.assertGreaterEqual(data["stats"]["files_total"], 1)


if __name__ == "__main__":
    unittest.main()
