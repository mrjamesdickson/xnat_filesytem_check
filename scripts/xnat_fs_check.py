#!/usr/bin/env python3
"""
XNAT Filesystem Check Plugin
Copyright (c) 2025 XNATWorks.
All rights reserved.

This software is distributed under the terms described in the LICENSE file.

Traverse XNAT projects and sessions to verify that referenced files exist on disk.

The script authenticates against an XNAT server, walks projects -> sessions
-> resources -> files, and validates that each file can be resolved to a local
filesystem path. Any missing or unresolved files are reported at the end.
"""

from __future__ import annotations

import argparse
import getpass
import csv
import json
import logging
import os
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from datetime import datetime, timezone
from html import escape
from pathlib import Path
from typing import Any, Callable, Dict, Iterator, List, Optional, Sequence, Tuple, Set
from urllib.parse import quote

import requests

LOG = logging.getLogger("xnat_fs_check")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Verify that files referenced by XNAT projects and sessions exist on the filesystem."
        )
    )
    parser.add_argument(
        "--base-url",
        required=True,
        help="XNAT base URL, e.g. https://xnat.example.com",
    )
    parser.add_argument(
        "--username",
        help="XNAT username; can also be provided via XNAT_USERNAME.",
    )
    parser.add_argument(
        "--password",
        help="XNAT password; can also be provided via XNAT_PASSWORD. "
        "If omitted, the script prompts when username is supplied.",
    )
    parser.add_argument(
        "--api-token",
        help="XNAT API token or bearer token; can also be provided via XNAT_API_TOKEN.",
    )
    parser.add_argument(
        "--data-root",
        type=Path,
        help="Filesystem root under which XNAT stores archive data. "
        "Relative file paths are resolved beneath this directory.",
    )
    parser.add_argument(
        "--project",
        action="append",
        dest="include_projects",
        help="Limit traversal to the given project ID or label. Can be repeated.",
    )
    parser.add_argument(
        "--project-id",
        help="Restrict traversal to a single project (shorthand for --project).",
    )
    parser.add_argument(
        "--skip-project",
        action="append",
        dest="skip_projects",
        help="Skip the given project ID or label. Can be repeated.",
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=30.0,
        help="HTTP request timeout in seconds (default: %(default)s).",
    )
    parser.add_argument(
        "--insecure",
        dest="verify_ssl",
        action="store_false",
        help="Disable TLS certificate verification.",
    )
    parser.add_argument(
        "--log-level",
        default="INFO",
        help="Logging level (default: %(default)s).",
    )
    parser.add_argument(
        "--fail-on-missing",
        action="store_true",
        help="Exit with status 2 when missing files are detected.",
    )
    parser.add_argument(
        "--path-key",
        action="append",
        dest="path_keys",
        help=(
            "Metadata key to interpret as a filesystem path. "
            "Defaults to a built-in ordered list; can be repeated to override."
        ),
    )
    parser.add_argument(
        "--max-files",
        type=int,
        help="Stop after checking this many files (useful for smoke tests).",
    )
    parser.add_argument(
        "--report-file",
        type=Path,
        help="Write a summary report to this path (supports JSON, text, or HTML).",
    )
    parser.add_argument(
        "--report-format",
        choices=("json", "text", "html"),
        default="json",
        help="Report format when --report-file is provided (default: %(default)s).",
    )
    parser.add_argument(
        "--report",
        action="append",
        metavar="FORMAT:PATH",
        help=(
            "Generate an additional report; format is json|text|html "
            "(example: json:/tmp/report.json)."
        ),
    )
    parser.add_argument(
        "--resource-report-file",
        type=Path,
        help="Write a per-resource detail report to this path (JSON or CSV).",
    )
    parser.add_argument(
        "--resource-report-format",
        choices=("json", "csv"),
        default="json",
        help="Format for --resource-report-file when provided (default: %(default)s).",
    )
    parser.add_argument(
        "--verify-catalogs",
        action="store_true",
        help="Scan catalog.xml files under the data root (or --catalog-root) and verify referenced files exist.",
    )
    parser.add_argument(
        "--catalog-root",
        type=Path,
        help="Root directory to search for catalog.xml files (defaults to --data-root).",
    )
    parser.set_defaults(verify_ssl=True)
    return parser


def _extract_results(payload: Dict) -> List[Dict]:
    """Handle the different JSON shapes returned by XNAT endpoints."""
    if not isinstance(payload, dict):
        return []
    result_set = payload.get("ResultSet") or payload.get("resultSet")
    if isinstance(result_set, dict):
        results = result_set.get("Result") or result_set.get("result")
        if isinstance(results, list):
            return results
    results = payload.get("Result") or payload.get("result")
    if isinstance(results, list):
        return results
    return []


def _first_value(row: Dict, keys: Sequence[str]) -> Optional[str]:
    for key in keys:
        value = row.get(key)
        if value not in (None, ""):
            return str(value)
    return None


class XNATClient:
    """Simple wrapper around the XNAT REST API."""

    def __init__(
        self,
        base_url: str,
        *,
        username: Optional[str] = None,
        password: Optional[str] = None,
        token: Optional[str] = None,
        verify_ssl: bool = True,
        timeout: float = 30.0,
    ) -> None:
        if not base_url:
            raise ValueError("base_url is required")
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.session = requests.Session()
        self.session.verify = verify_ssl
        if token:
            self.session.headers.update({"Authorization": f"Bearer {token}"})
        elif username:
            if password is None:
                raise ValueError("password must be provided when username is set")
            self.session.auth = (username, password)

    def close(self) -> None:
        self.session.close()

    def _request_json(self, path_or_url: str, params: Optional[Dict] = None) -> Dict:
        if path_or_url.startswith("http"):
            url = path_or_url
        else:
            if not path_or_url.startswith("/"):
                path_or_url = f"/{path_or_url}"
            url = f"{self.base_url}{path_or_url}"
        response = self.session.get(url, params=params, timeout=self.timeout)
        response.raise_for_status()
        try:
            return response.json()
        except ValueError as exc:  # pragma: no cover - defensive
            raise RuntimeError(f"Non-JSON response from {url}") from exc

    def iter_projects(self) -> Iterator[Dict]:
        payload = self._request_json("/data/archive/projects", params={"format": "json"})
        yield from _extract_results(payload)

    def iter_project_experiments(self, project_row: Dict) -> Iterator[Dict]:
        project_uri = project_row.get("URI") or project_row.get("uri")
        if project_uri:
            path = project_uri.rstrip("/") + "/experiments"
        else:
            project_id = _first_value(
                project_row, ("ID", "id", "project", "project_id", "label", "name")
            )
            if not project_id:
                raise RuntimeError("Project row is missing an identifier")
            path = f"/data/archive/projects/{quote(project_id)}/experiments"
        payload = self._request_json(path, params={"format": "json"})
        yield from _extract_results(payload)

    def iter_experiment_resources(self, experiment_row: Dict) -> Iterator[Dict]:
        experiment_uri = experiment_row.get("URI") or experiment_row.get("uri")
        if experiment_uri:
            path = experiment_uri.rstrip("/") + "/resources"
        else:
            experiment_id = _first_value(
                experiment_row, ("ID", "id", "xnat_abstractresource_id", "label", "name")
            )
            if not experiment_id:
                raise RuntimeError("Experiment row is missing an identifier")
            path = f"/data/archive/experiments/{quote(experiment_id)}/resources"
        payload = self._request_json(path, params={"format": "json"})
        yield from _extract_results(payload)

    def iter_resource_files(
        self,
        resource_row: Dict,
        *,
        experiment_uri: Optional[str] = None,
        resource_label: Optional[str] = None,
        scan_id: Optional[str] = None,
        assessor_id: Optional[str] = None,
    ) -> Iterator[Dict]:
        resource_uri = resource_row.get("URI") or resource_row.get("uri")
        if resource_uri:
            path = resource_uri.rstrip("/") + "/files"
        else:
            # Build scoped path using the experiment URI (e.g. /data/experiments/XNAT_E00818)
            # which is more reliable than /data/archive/projects/{label}/experiments/{label}.
            if experiment_uri and resource_label:
                base = experiment_uri.rstrip("/")
                if scan_id:
                    path = f"{base}/scans/{quote(scan_id)}/resources/{quote(resource_label)}/files"
                elif assessor_id:
                    path = f"{base}/assessors/{quote(assessor_id)}/resources/{quote(resource_label)}/files"
                else:
                    path = f"{base}/resources/{quote(resource_label)}/files"
            else:
                resource_id = _first_value(
                    resource_row, ("ID", "id", "xnat_abstractresource_id", "label", "name")
                )
                if not resource_id:
                    raise RuntimeError("Resource row is missing an identifier")
                path = f"/data/archive/resources/{quote(resource_id)}/files"
        payload = self._request_json(path, params={"format": "json"})
        yield from _extract_results(payload)

    def iter_experiment_scans(self, experiment_row: Dict) -> Iterator[Dict]:
        experiment_uri = experiment_row.get("URI") or experiment_row.get("uri")
        if experiment_uri:
            path = experiment_uri.rstrip("/") + "/scans"
        else:
            experiment_id = _first_value(
                experiment_row, ("ID", "id", "xnat_abstractresource_id", "label", "name")
            )
            if not experiment_id:
                raise RuntimeError("Experiment row is missing an identifier")
            path = f"/data/archive/experiments/{quote(experiment_id)}/scans"
        payload = self._request_json(path, params={"format": "json"})
        yield from _extract_results(payload)

    def iter_scan_resources(self, experiment_row: Dict, scan_row: Dict) -> Iterator[Dict]:
        scan_uri = scan_row.get("URI") or scan_row.get("uri")
        if scan_uri:
            path = scan_uri.rstrip("/") + "/resources"
        else:
            experiment_id = _first_value(
                experiment_row, ("ID", "id", "xnat_abstractresource_id", "label", "name")
            )
            scan_id = _first_value(
                scan_row, ("ID", "id", "scan_id", "label", "xnat_imagescandata_id")
            )
            if not experiment_id or not scan_id:
                raise RuntimeError("Scan row is missing an identifier")
            path = (
                f"/data/archive/experiments/{quote(experiment_id)}/scans/{quote(scan_id)}/resources"
            )
        payload = self._request_json(path, params={"format": "json"})
        yield from _extract_results(payload)

    def iter_experiment_assessors(self, experiment_row: Dict) -> Iterator[Dict]:
        experiment_uri = experiment_row.get("URI") or experiment_row.get("uri")
        if experiment_uri:
            path = experiment_uri.rstrip("/") + "/assessors"
        else:
            experiment_id = _first_value(
                experiment_row, ("ID", "id", "xnat_abstractresource_id", "label", "name")
            )
            if not experiment_id:
                raise RuntimeError("Experiment row is missing an identifier")
            path = f"/data/archive/experiments/{quote(experiment_id)}/assessors"
        payload = self._request_json(path, params={"format": "json"})
        yield from _extract_results(payload)

    def iter_assessor_resources(self, experiment_row: Dict, assessor_row: Dict) -> Iterator[Dict]:
        assessor_uri = assessor_row.get("URI") or assessor_row.get("uri")
        if assessor_uri:
            path = assessor_uri.rstrip("/") + "/resources"
        else:
            experiment_id = _first_value(
                experiment_row, ("ID", "id", "xnat_abstractresource_id", "label", "name")
            )
            assessor_id = _first_value(
                assessor_row,
                ("ID", "id", "label", "assessor_id", "xnat_imageassessordata_id", "name"),
            )
            if not experiment_id or not assessor_id:
                raise RuntimeError("Assessor row is missing an identifier")
            path = (
                f"/data/archive/experiments/{quote(experiment_id)}/assessors/{quote(assessor_id)}/resources"
            )
        payload = self._request_json(path, params={"format": "json"})
        yield from _extract_results(payload)


@dataclass
class CheckReport:
    stats: Dict[str, int] = field(default_factory=dict)
    project_summaries: Dict[str, Dict[str, int]] = field(default_factory=dict)
    missing_files: List[Dict[str, str]] = field(default_factory=list)
    unresolved_files: List[Dict[str, str]] = field(default_factory=list)
    resource_details: List[Dict[str, Any]] = field(default_factory=list)
    catalog_missing_entries: List[Dict[str, str]] = field(default_factory=list)
    catalog_errors: List[Dict[str, str]] = field(default_factory=list)
    generated_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))

    def to_dict(self) -> Dict[str, object]:
        return {
            "generated_at": self.generated_at.isoformat(),
            "stats": dict(self.stats),
            "project_summaries": dict(self.project_summaries),
            "missing_files": list(self.missing_files),
            "unresolved_files": list(self.unresolved_files),
            "resource_details": list(self.resource_details),
            "catalog_missing_entries": list(self.catalog_missing_entries),
            "catalog_errors": list(self.catalog_errors),
        }

    def to_text(self) -> str:
        lines = [
            f"Generated at: {self.generated_at.isoformat()}",
            (
                "Projects: {projects} | Sessions: {sessions} | Resources: {resources} | "
                "Files checked: {files_total}"
            ).format(**self.stats),
            "Scans: {scans} | Assessors: {assessors}".format(**self.stats),
            (
                "Session resources: {session_resources} | Scan resources: {scan_resources} | "
                "Assessor resources: {assessor_resources}"
            ).format(**self.stats),
            (
                "Files found: {files_found} | Missing: {files_missing} | Unresolved: {files_unresolved}"
            ).format(**self.stats),
            (
                "Catalogs checked: {catalogs_total} | Catalog entries: {catalog_entries_total} | "
                "Missing catalog entries: {catalog_entries_missing} | Catalog errors: {catalogs_with_errors}"
            ).format(**self.stats),
        ]

        if self.project_summaries:
            lines.append("")
            lines.append("Per-Project Summary:")
            lines.append("-" * 80)
            for project, pstats in sorted(self.project_summaries.items()):
                lines.append(
                    f"  {project}: sessions={pstats.get('sessions', 0)} "
                    f"files={pstats.get('files_total', 0)} "
                    f"found={pstats.get('files_found', 0)} "
                    f"missing={pstats.get('files_missing', 0)} "
                    f"unresolved={pstats.get('files_unresolved', 0)}"
                )

        if self.missing_files:
            lines.append("Missing files:")
            for entry in self.missing_files:
                lines.append(
                    "  project={project} session={session} resource={resource} scope={scope} path={path}".format(
                        path=entry.get("path", ""),
                        **{k: entry.get(k, "") for k in ("project", "session", "resource", "scope")}
                    )
                )
        if self.unresolved_files:
            lines.append("Unresolved files:")
            for entry in self.unresolved_files:
                lines.append(
                    "  project={project} session={session} resource={resource} scope={scope} file={file}".format(
                        file=entry.get("file", ""),
                        **{k: entry.get(k, "") for k in ("project", "session", "resource", "scope")}
                    )
                )
        if self.catalog_missing_entries:
            lines.append("Missing catalog entries:")
            for entry in self.catalog_missing_entries:
                lines.append(
                    "  catalog={catalog} entry={entry_uri} resolved_path={resolved_path}".format(
                        **entry
                    )
                )
        if self.catalog_errors:
            lines.append("Catalog errors:")
            for entry in self.catalog_errors:
                lines.append(
                    "  catalog={catalog} error={error}".format(**entry)
                )
        return "\n".join(lines)

    def to_html(self) -> str:
        stat_sections = [
            (
                "Project Summary",
                [
                    ("projects", "Projects"),
                    ("sessions", "Sessions"),
                    ("scans", "Scans"),
                    ("assessors", "Assessors"),
                ],
            ),
            (
                "Filesystem Results",
                [
                    ("files_total", "Files Checked"),
                    ("files_found", "Files Found"),
                    ("files_missing", "Files Missing"),
                    ("files_unresolved", "Files Unresolved"),
                ],
            ),
            (
                "Resource Breakdown",
                [
                    ("resources", "Total Resources"),
                    ("session_resources", "Session Resources"),
                    ("scan_resources", "Scan Resources"),
                    ("assessor_resources", "Assessor Resources"),
                ],
            ),
            (
                "Catalog Results",
                [
                    ("catalogs_total", "Catalogs Checked"),
                    ("catalog_entries_total", "Catalog Entries"),
                    ("catalog_entries_missing", "Missing Catalog Entries"),
                    ("catalogs_with_errors", "Catalogs With Errors"),
                ],
            ),
        ]

        def render_table(
            entries: List[Dict[str, Any]],
            columns: Sequence[Tuple[str, str]],
            row_class: Optional[Callable[[Dict[str, Any]], str]] = None,
            table_class: str = "detail sortable",
        ) -> str:
            numeric_keys = {
                "files_total",
                "files_found",
                "files_missing",
                "files_unresolved",
                "resources",
                "session_resources",
                "scan_resources",
                "assessor_resources",
                "catalogs_total",
                "catalog_entries_total",
                "catalog_entries_missing",
                "catalogs_with_errors",
                "files_listed",
            }
            processed_cols: List[Tuple[str, str, str]] = []
            for col in columns:
                if len(col) == 3:
                    key, label, col_type = col
                else:
                    key, label = col
                    col_type = "number" if key in numeric_keys else "text"
                processed_cols.append((key, label, col_type))

            header_html = "".join(
                f"<th data-type=\"{escape(col_type)}\">{escape(label)}</th>"
                for _, label, col_type in processed_cols
            )
            rows_html = "".join(
                "<tr"
                + (f" class=\"{escape(row_class(entry))}\"" if row_class else "")
                + ">"
                + "".join(
                    f"<td>{escape(str(entry.get(key, '')))}</td>" for key, _, _ in processed_cols
                )
                + "</tr>"
                for entry in entries
            )
            return (
                f"<table class=\"{table_class}\">"
                "<thead><tr>" + header_html + "</tr></thead>"
                "<tbody>" + rows_html + "</tbody>"
                "</table>"
            )

        missing_section = ""
        if self.missing_files:
            missing_section = (
                "<h2>Missing Files</h2>"
                + render_table(
                    self.missing_files,
                    [
                        ("project", "Project"),
                        ("session", "Session"),
                        ("resource", "Resource"),
                        ("scope", "Scope"),
                        ("scan", "Scan"),
                        ("assessor", "Assessor"),
                        ("path", "Path"),
                    ],
                    table_class="detail sortable",
                )
            )

        unresolved_section = ""
        if self.unresolved_files:
            unresolved_section = (
                "<h2>Unresolved Files</h2>"
                + render_table(
                    self.unresolved_files,
                    [
                        ("project", "Project"),
                        ("session", "Session"),
                        ("resource", "Resource"),
                        ("scope", "Scope"),
                        ("scan", "Scan"),
                        ("assessor", "Assessor"),
                        ("file", "File"),
                        ("error", "Error"),
                    ],
                    table_class="detail sortable",
                )
            )

        resource_section = ""
        if self.resource_details:
            resource_section = (
                "<h2>Resource Details</h2>"
                + render_table(
                    self.resource_details,
                    [
                        ("project", "Project"),
                        ("session", "Session"),
                        ("scope", "Scope"),
                        ("scan", "Scan"),
                        ("assessor", "Assessor"),
                        ("resource", "Resource"),
                        ("status", "Status"),
                        ("files_listed", "Files Listed", "number"),
                        ("catalog_path", "Catalog"),
                        ("error", "Error"),
                    ],
                    row_class=lambda entry: self.highlight_class(entry),
                    table_class="detail sortable",
                )
            )

        catalog_missing_section = ""
        if self.catalog_missing_entries:
            catalog_missing_section = (
                "<h2>Missing Catalog Entries</h2>"
                + render_table(
                    self.catalog_missing_entries,
                    [
                        ("catalog", "Catalog"),
                        ("entry_uri", "Entry URI"),
                        ("resolved_path", "Resolved Path"),
                    ],
                )
            )

        catalog_error_section = ""
        if self.catalog_errors:
            catalog_error_section = (
                "<h2>Catalog Errors</h2>"
                + render_table(
                    self.catalog_errors,
                    [
                        ("catalog", "Catalog"),
                        ("error", "Error"),
                    ],
                )
            )

        return (
            "<!DOCTYPE html>"
            "<html lang=\"en\">"
            "<head>"
            "<meta charset=\"utf-8\"/>"
            "<title>XNAT Filesystem Check Report</title>"
            "<style>"
            "body{font-family:Arial,Helvetica,sans-serif;margin:20px;color:#222;background:#fff;}"
            "h1{font-size:1.9em;margin-bottom:0.4em;}"
            "time{color:#555;}"
            "table{border-collapse:collapse;width:100%;margin:1em 0;cursor:pointer;}"
            "th,td{border:1px solid #ccc;padding:0.4em 0.6em;text-align:left;font-size:0.95em;}"
            "th{background:#f0f0f0;position:relative;}"
            "table.detail tbody tr:nth-child(even){background:#fafafa;}"
            ".section{margin-bottom:2em;border:1px solid #ddd;border-radius:6px;padding:1em;}"
            ".section h2{margin-top:0;font-size:1.2em;}"
            ".status-ok{color:#0a662a;font-weight:600;}"
            ".status-warning{color:#b8860b;font-weight:600;}"
            ".status-error{color:#a40000;font-weight:600;}"
            ".sortable th::after{content:'\\25B4\\25BE';position:absolute;right:0.4em;color:#aaa;font-size:0.8em;}"
            "</style>"
            "<script>"
            "function sortTable(table, col, numeric){"
            " const tbody = table.tBodies[0];"
            " const rows = Array.from(tbody.querySelectorAll('tr'));"
            " const current = table.getAttribute('data-sort-col') == col ? table.getAttribute('data-sort-dir') : null;"
            " const dir = current === 'asc' ? 'desc' : 'asc';"
            " rows.sort((a,b)=>{"
            "   const A = a.children[col].innerText.trim();"
            "   const B = b.children[col].innerText.trim();"
            "   if(numeric){"
            "     const numA = parseFloat(A) || 0;"
            "     const numB = parseFloat(B) || 0;"
            "     return dir==='asc' ? numA-numB : numB-numA;"
            "   }"
            "   return dir==='asc' ? A.localeCompare(B) : B.localeCompare(A);"
            " });"
            " rows.forEach(row=>tbody.appendChild(row));"
            " table.setAttribute('data-sort-col', col);"
            " table.setAttribute('data-sort-dir', dir);"
            "}"
            "document.addEventListener('DOMContentLoaded',()=>{"
            " document.querySelectorAll('table.sortable').forEach(table=>{"
            "   table.querySelectorAll('th').forEach((th,idx)=>{"
            "     th.addEventListener('click', ()=>{"
            "       const numeric = th.getAttribute('data-type') === 'number';"
            "       sortTable(table, idx, numeric);"
            "     });"
            "   });"
            " });"
            "});"
            "</script>"
            "</head>"
            "<body>"
            "<h1>XNAT Filesystem Check Report</h1>"
            f"<p>Generated at: <time>{escape(self.generated_at.isoformat())}</time></p>"
            + "".join(
                "<div class=\"section\">"
                f"<h2>{escape(title)}</h2>"
                "<table class=\"summary sortable\">"
                + "".join(
                    self._format_stat_row(label, self.stats.get(key, 0))
                    for key, label in fields
                )
                + "</table>"
                "</div>"
                for title, fields in stat_sections
            )
            + self._render_project_summary_section(render_table)
            + missing_section
            + unresolved_section
            + resource_section
            + catalog_missing_section
            + catalog_error_section
            + "</body></html>"
        )

    def _render_project_summary_section(self, render_table) -> str:
        if not self.project_summaries:
            return ""
        # Convert to list of dicts for the table renderer
        project_rows = [
            {
                "project": project,
                "sessions": pstats.get("sessions", 0),
                "scans": pstats.get("scans", 0),
                "assessors": pstats.get("assessors", 0),
                "resources": pstats.get("resources", 0),
                "files_total": pstats.get("files_total", 0),
                "files_found": pstats.get("files_found", 0),
                "files_missing": pstats.get("files_missing", 0),
                "files_unresolved": pstats.get("files_unresolved", 0),
            }
            for project, pstats in sorted(self.project_summaries.items())
        ]

        def row_class(entry):
            if entry.get("files_missing", 0) > 0:
                return "status-error"
            if entry.get("files_unresolved", 0) > 0:
                return "status-warning"
            return "status-ok"

        return (
            "<div class=\"section\">"
            "<h2>Per-Project Summary</h2>"
            + render_table(
                project_rows,
                [
                    ("project", "Project"),
                    ("sessions", "Sessions", "number"),
                    ("scans", "Scans", "number"),
                    ("assessors", "Assessors", "number"),
                    ("resources", "Resources", "number"),
                    ("files_total", "Files Checked", "number"),
                    ("files_found", "Files Found", "number"),
                    ("files_missing", "Files Missing", "number"),
                    ("files_unresolved", "Unresolved", "number"),
                ],
                row_class=row_class,
                table_class="detail sortable",
            )
            + "</div>"
        )

    @staticmethod
    def _format_stat_row(label: str, value: object) -> str:
        try:
            numeric_value = float(value)
        except (TypeError, ValueError):
            numeric_value = None
        status_class = ""
        if numeric_value is not None:
            if label.lower().startswith("files missing") or label.lower().startswith("missing catalog"):
                status_class = " class=\"status-error\"" if numeric_value > 0 else " class=\"status-ok\""
            elif label.lower().startswith("files unresolved") or label.lower().startswith("catalogs with errors"):
                status_class = " class=\"status-error\"" if numeric_value > 0 else " class=\"status-ok\""
        col_type = "number" if numeric_value is not None else "text"
        return f"<tr><th data-type=\"{col_type}\">{escape(label)}</th><td{status_class}>{escape(str(value))}</td></tr>"

    @staticmethod
    def highlight_class(resource: Dict[str, Any]) -> str:
        status = resource.get("status", "")
        if status == "error":
            return "status-error"
        if status == "ok" and int(resource.get("files_listed", 0)) == 0:
            return "status-warning"
        return "status-ok"


class FilesystemChecker:
    """Walk XNAT metadata and confirm that referenced files exist locally."""

    DEFAULT_PATH_KEYS: Sequence[str] = (
        "absolutePath",
        "absolute_path",
        "cachepath",
        "cache_path",
        "path",
        "filepath",
        "file_path",
        "relativePath",
    )

    def __init__(
        self,
        client: XNATClient,
        *,
        data_root: Optional[Path],
        path_keys: Optional[Sequence[str]] = None,
        max_files: Optional[int] = None,
        verify_catalogs: bool = False,
        catalog_root: Optional[Path] = None,
    ) -> None:
        self.client = client
        self.data_root = data_root.resolve(strict=False) if data_root else None
        self.path_keys = tuple(path_keys) if path_keys else self.DEFAULT_PATH_KEYS
        self.max_files = max_files
        self.verify_catalogs = verify_catalogs
        if catalog_root:
            self.catalog_root = catalog_root.resolve(strict=False)
        else:
            self.catalog_root = self.data_root
        self._max_files_logged = False

    def check(
        self,
        include_projects: Optional[Sequence[str]] = None,
        skip_projects: Optional[Sequence[str]] = None,
    ) -> CheckReport:
        stats = {
            "projects": 0,
            "sessions": 0,
            "scans": 0,
            "assessors": 0,
            "resources": 0,
            "session_resources": 0,
            "scan_resources": 0,
            "assessor_resources": 0,
            "files_total": 0,
            "files_found": 0,
            "files_missing": 0,
            "files_unresolved": 0,
            "catalogs_total": 0,
            "catalog_entries_total": 0,
            "catalog_entries_missing": 0,
            "catalogs_with_errors": 0,
        }
        resource_records: List[Dict[str, Any]] = []
        report = CheckReport(stats=stats, resource_details=resource_records)
        catalog_paths: Set[Path] = set()

        include = {p.lower() for p in include_projects or []}
        skip = {p.lower() for p in skip_projects or []}

        try:
            for project_row in self.client.iter_projects():
                project_label = self._project_label(project_row)
                if include and project_label.lower() not in include:
                    LOG.debug("Skipping project %s (not in include list)", project_label)
                    continue
                if skip and project_label.lower() in skip:
                    LOG.debug("Skipping project %s (skip list)", project_label)
                    continue

                stats["projects"] += 1
                LOG.info("Project: %s", project_label)

                # Initialize per-project stats
                project_stats = {
                    "sessions": 0,
                    "scans": 0,
                    "assessors": 0,
                    "resources": 0,
                    "files_total": 0,
                    "files_found": 0,
                    "files_missing": 0,
                    "files_unresolved": 0,
                }
                report.project_summaries[project_label] = project_stats

                try:
                    experiments = list(self.client.iter_project_experiments(project_row))
                except requests.RequestException as exc:
                    LOG.error("Failed to list sessions for %s: %s", project_label, exc)
                    continue

                for experiment_row in experiments:
                    session_label = self._experiment_label(experiment_row)
                    experiment_uri = experiment_row.get("URI") or experiment_row.get("uri")
                    stats["sessions"] += 1
                    project_stats["sessions"] += 1
                    LOG.debug("  Session: %s", session_label)

                    try:
                        resources = list(self.client.iter_experiment_resources(experiment_row))
                    except requests.RequestException as exc:
                        LOG.error(
                            "Failed to list resources for %s/%s: %s",
                            project_label,
                            session_label,
                            exc,
                        )
                        continue

                    for resource_row in resources:
                        if self._process_resource(
                            report,
                            stats,
                            catalog_paths,
                            resource_row,
                            project_label=project_label,
                            session_label=session_label,
                            experiment_uri=experiment_uri,
                            scope="session",
                            project_stats=project_stats,
                        ):
                            if self.verify_catalogs:
                                self._verify_catalog_paths(report, catalog_paths)
                            return report

                    try:
                        scans = list(self.client.iter_experiment_scans(experiment_row))
                    except requests.RequestException as exc:
                        LOG.error(
                            "Failed to list scans for %s/%s: %s",
                            project_label,
                            session_label,
                            exc,
                        )
                        scans = []

                    for scan_row in scans:
                        scan_label = self._scan_label(scan_row)
                        stats["scans"] += 1
                        project_stats["scans"] += 1
                        LOG.debug("    Scan: %s", scan_label)

                        try:
                            scan_resources = list(
                                self.client.iter_scan_resources(experiment_row, scan_row)
                            )
                        except requests.RequestException as exc:
                            LOG.error(
                                "Failed to list scan resources for %s/%s/%s: %s",
                                project_label,
                                session_label,
                                scan_label,
                                exc,
                            )
                            continue

                        for resource_row in scan_resources:
                            if self._process_resource(
                                report,
                                stats,
                                catalog_paths,
                                resource_row,
                                project_label=project_label,
                                session_label=session_label,
                                experiment_uri=experiment_uri,
                                scope="scan",
                                scan_id=scan_label,
                                project_stats=project_stats,
                            ):
                                if self.verify_catalogs:
                                    self._verify_catalog_paths(report, catalog_paths)
                                return report

                    try:
                        assessors = list(self.client.iter_experiment_assessors(experiment_row))
                    except requests.RequestException as exc:
                        LOG.error(
                            "Failed to list assessors for %s/%s: %s",
                            project_label,
                            session_label,
                            exc,
                        )
                        assessors = []

                    for assessor_row in assessors:
                        assessor_label = self._assessor_label(assessor_row)
                        stats["assessors"] += 1
                        project_stats["assessors"] += 1
                        LOG.debug("    Assessor: %s", assessor_label)

                        try:
                            assessor_resources = list(
                                self.client.iter_assessor_resources(experiment_row, assessor_row)
                            )
                        except requests.RequestException as exc:
                            LOG.error(
                                "Failed to list assessor resources for %s/%s/%s: %s",
                                project_label,
                                session_label,
                                assessor_label,
                                exc,
                            )
                            continue

                        for resource_row in assessor_resources:
                            if self._process_resource(
                                report,
                                stats,
                                catalog_paths,
                                resource_row,
                                project_label=project_label,
                                session_label=session_label,
                                experiment_uri=experiment_uri,
                                scope="assessor",
                                assessor_id=assessor_label,
                                project_stats=project_stats,
                            ):
                                if self.verify_catalogs:
                                    self._verify_catalog_paths(report, catalog_paths)
                                return report
        finally:
            self.client.close()

        if self.verify_catalogs:
            self._verify_catalog_paths(report, catalog_paths)

        return report

    @staticmethod
    def _project_label(project_row: Dict) -> str:
        return _first_value(project_row, ("label", "name", "ID", "id", "project")) or "UNKNOWN_PROJECT"

    @staticmethod
    def _experiment_label(experiment_row: Dict) -> str:
        return _first_value(
            experiment_row,
            (
                "label",
                "name",
                "session_label",
                "sessionId",
                "externalId",
                "ID",
                "id",
            ),
        ) or "UNKNOWN_SESSION"

    @staticmethod
    def _resource_label(resource_row: Dict) -> str:
        return _first_value(
            resource_row,
            ("label", "name", "resource_label", "xnat_abstractresource_id", "ID", "id"),
        ) or "UNKNOWN_RESOURCE"

    @staticmethod
    def _scan_label(scan_row: Dict) -> str:
        return _first_value(
            scan_row,
            (
                "ID",
                "id",
                "scan_id",
                "series_description",
                "label",
                "xnat_imagescandata_id",
            ),
        ) or "UNKNOWN_SCAN"

    @staticmethod
    def _assessor_label(assessor_row: Dict) -> str:
        return _first_value(
            assessor_row,
            (
                "ID",
                "id",
                "label",
                "xnat_imageassessordata_id",
                "assessor_id",
                "name",
            ),
        ) or "UNKNOWN_ASSESSOR"

    @staticmethod
    def _file_label(file_row: Dict) -> str:
        return _first_value(file_row, ("Name", "name", "label", "URI", "uri", "id")) or "UNKNOWN_FILE"

    def _process_resource(
        self,
        report: CheckReport,
        stats: Dict[str, int],
        catalog_paths: Set[Path],
        resource_row: Dict,
        *,
        project_label: str,
        session_label: str,
        experiment_uri: Optional[str] = None,
        scope: str,
        scan_id: Optional[str] = None,
        assessor_id: Optional[str] = None,
        project_stats: Optional[Dict[str, int]] = None,
    ) -> bool:
        resource_label = self._resource_label(resource_row)
        stats["resources"] += 1
        if project_stats is not None:
            project_stats["resources"] += 1
        if scope == "session":
            stats["session_resources"] += 1
        elif scope == "scan":
            stats["scan_resources"] += 1
        elif scope == "assessor":
            stats["assessor_resources"] += 1

        resource_uri = resource_row.get("URI") or resource_row.get("uri")
        resource_record: Dict[str, Any] = {
            "project": project_label,
            "session": session_label,
            "resource": resource_label,
            "resource_uri": resource_uri,
            "status": "unknown",
            "files_listed": 0,
            "scope": scope,
        }
        if scan_id is not None:
            resource_record["scan"] = scan_id
        if assessor_id is not None:
            resource_record["assessor"] = assessor_id

        catalog_path: Optional[Path] = None
        base_dir: Optional[Path] = None
        if self.verify_catalogs:
            catalog_path = self._resolve_catalog_path(
                resource_row,
                project_label=project_label,
                session_label=session_label,
                resource_label=resource_label,
                scan_id=scan_id,
                assessor_id=assessor_id,
            )
            if catalog_path:
                resource_record["catalog_path"] = str(catalog_path)
                catalog_paths.add(catalog_path)
                base_dir = catalog_path.parent

        if base_dir is None:
            base_dir = self._resource_dir(
                project_label,
                session_label,
                resource_label,
                scan_id=scan_id,
                assessor_id=assessor_id,
            )

        try:
            file_rows = list(
                self.client.iter_resource_files(
                    resource_row,
                    experiment_uri=experiment_uri,
                    resource_label=resource_label,
                    scan_id=scan_id,
                    assessor_id=assessor_id,
                )
            )
            resource_record["status"] = "ok"
            resource_record["files_listed"] = len(file_rows)
        except requests.RequestException as exc:
            resource_record["status"] = "error"
            resource_record["error"] = str(exc)
            if scan_id is None:
                resource_record.setdefault("scan", "")
            if assessor_id is None:
                resource_record.setdefault("assessor", "")
            resource_record.setdefault("catalog_path", "")
            report.resource_details.append(resource_record)
            LOG.error(
                "Failed to list files for %s/%s/%s (%s scope): %s",
                project_label,
                session_label,
                resource_label,
                scope,
                exc,
            )
            return False

        for file_row in file_rows:
            if self.max_files and stats["files_total"] >= self.max_files:
                if not self._max_files_logged:
                    LOG.warning("Reached max-files=%d threshold, stopping early.", self.max_files)
                    self._max_files_logged = True
                report.resource_details.append(resource_record)
                return True

            stats["files_total"] += 1
            if project_stats is not None:
                project_stats["files_total"] += 1
            file_label = self._file_label(file_row)
            resolved_path = self._resolve_path(file_row, base_dir)

            if resolved_path is None:
                stats["files_unresolved"] += 1
                if project_stats is not None:
                    project_stats["files_unresolved"] += 1
                LOG.warning(
                    "      Unresolved path: %s/%s/%s (%s scope) -> %s",
                    project_label,
                    session_label,
                    resource_label,
                    scope,
                    file_label,
                )
                entry = {
                    "project": project_label,
                    "session": session_label,
                    "resource": resource_label,
                    "file": file_label,
                    "scope": scope,
                }
                if scan_id is not None:
                    entry["scan"] = scan_id
                if assessor_id is not None:
                    entry["assessor"] = assessor_id
                report.unresolved_files.append(entry)
                continue

            relative_entry = self._relative_entry_path(file_row)

            try:
                path_exists = resolved_path.exists()
            except OSError as exc:
                stats["files_unresolved"] += 1
                if project_stats is not None:
                    project_stats["files_unresolved"] += 1
                LOG.error(
                    "      Access error: %s/%s/%s (%s scope) -> %s (%s)",
                    project_label,
                    session_label,
                    resource_label,
                    scope,
                    resolved_path,
                    exc,
                )
                entry = {
                    "project": project_label,
                    "session": session_label,
                    "resource": resource_label,
                    "file": file_label,
                    "scope": scope,
                    "error": str(exc),
                }
                if scan_id is not None:
                    entry["scan"] = scan_id
                if assessor_id is not None:
                    entry["assessor"] = assessor_id
                report.unresolved_files.append(entry)
                continue

            if not path_exists and relative_entry is not None:
                fallback_bases: List[Optional[Path]] = []
                if scope != "session":
                    fallback_bases.append(
                        self._resource_dir(project_label, session_label, resource_label)
                    )
                fallback_bases.append(self.data_root)
                for base in fallback_bases:
                    if not base:
                        continue
                    candidate = (base / relative_entry).resolve(strict=False)
                    if candidate == resolved_path:
                        continue
                    try:
                        if candidate.exists():
                            resolved_path = candidate
                            path_exists = True
                            break
                    except OSError:
                        continue

            if path_exists:
                stats["files_found"] += 1
                if project_stats is not None:
                    project_stats["files_found"] += 1
                LOG.debug(
                    "      OK: %s/%s/%s (%s scope) -> %s",
                    project_label,
                    session_label,
                    resource_label,
                    scope,
                    resolved_path,
                )
            else:
                stats["files_missing"] += 1
                if project_stats is not None:
                    project_stats["files_missing"] += 1
                LOG.error(
                    "      Missing: %s/%s/%s (%s scope) -> %s",
                    project_label,
                    session_label,
                    resource_label,
                    scope,
                    resolved_path,
                )
                entry = {
                    "project": project_label,
                    "session": session_label,
                    "resource": resource_label,
                    "file": file_label,
                    "path": str(resolved_path),
                    "scope": scope,
                }
                if scan_id is not None:
                    entry["scan"] = scan_id
                if assessor_id is not None:
                    entry["assessor"] = assessor_id
                report.missing_files.append(entry)

        if scan_id is None:
            resource_record.setdefault("scan", "")
        if assessor_id is None:
            resource_record.setdefault("assessor", "")
        resource_record.setdefault("catalog_path", "")
        report.resource_details.append(resource_record)
        return False

    def _resolve_path(self, file_row: Dict, base_dir: Optional[Path]) -> Optional[Path]:
        for key in self.path_keys:
            value = file_row.get(key) or file_row.get(key.lower())
            if not value:
                continue
            candidate = Path(value)
            if not candidate.is_absolute():
                if base_dir:
                    candidate = (base_dir / value).resolve(strict=False)
                elif self.data_root:
                    candidate = (self.data_root / value).resolve(strict=False)
                else:
                    continue
            return candidate

        uri_value = file_row.get("URI") or file_row.get("uri")
        if uri_value:
            _, _, suffix = uri_value.partition("/files/")
            if suffix:
                if base_dir:
                    return (base_dir / suffix).resolve(strict=False)
                if self.data_root:
                    return (self.data_root / suffix).resolve(strict=False)

        return None

    def _relative_entry_path(self, file_row: Dict) -> Optional[Path]:
        uri_value = file_row.get("URI") or file_row.get("uri")
        if uri_value:
            if uri_value.startswith("/"):
                _, _, suffix = uri_value.partition("/files/")
                if suffix:
                    return Path(suffix)
                return Path(uri_value.lstrip("/"))
            return Path(uri_value)
        name_value = file_row.get("Name") or file_row.get("name")
        if name_value:
            return Path(name_value)
        return None

    def _resource_dir(
        self,
        project_label: str,
        session_label: str,
        resource_label: str,
        scan_id: Optional[str] = None,
        assessor_id: Optional[str] = None,
    ) -> Optional[Path]:
        if not self.data_root:
            return None
        cache_key = (project_label, session_label, resource_label, scan_id, assessor_id)
        cache = getattr(self, "_resource_dir_cache", None)
        if cache is None:
            cache = {}
            setattr(self, "_resource_dir_cache", cache)
        if cache_key in cache:
            return cache[cache_key]

        project_dir = self.data_root / project_label
        if not project_dir.exists():
            cache[cache_key] = None
            return None

        # Common XNAT archives use arcNNN directories beneath the project.
        # Common archive layout: arcNNN directories under project -> session -> RESOURCES -> resource.
        for arc_dir in sorted(project_dir.glob("arc*")):
            base_session_dir = arc_dir / session_label

            if scan_id:
                candidates = [
                    base_session_dir / "SCANS" / scan_id / resource_label,
                    base_session_dir / "SCANS" / scan_id / "RESOURCES" / resource_label,
                ]
                for candidate in candidates:
                    if candidate.exists():
                        cache[cache_key] = candidate
                        return candidate

            if assessor_id:
                candidates = [
                    base_session_dir / "ASSESSORS" / assessor_id / resource_label,
                    base_session_dir
                    / "ASSESSORS"
                    / assessor_id
                    / "RESOURCES"
                    / resource_label,
                ]
                for candidate in candidates:
                    if candidate.exists():
                        cache[cache_key] = candidate
                        return candidate

            candidate = base_session_dir / "RESOURCES" / resource_label
            if candidate.exists():
                cache[cache_key] = candidate
                return candidate
            candidate_lower = base_session_dir / "resources" / resource_label
            if candidate_lower.exists():
                cache[cache_key] = candidate_lower
                return candidate_lower

        # Alternative: session folders directly under project.
        if scan_id:
            candidates = [
                project_dir / session_label / "SCANS" / scan_id / resource_label,
                project_dir / session_label / "SCANS" / scan_id / "RESOURCES" / resource_label,
            ]
            for candidate in candidates:
                if candidate.exists():
                    cache[cache_key] = candidate
                    return candidate

        if assessor_id:
            candidates = [
                project_dir / session_label / "ASSESSORS" / assessor_id / resource_label,
                project_dir
                / session_label
                / "ASSESSORS"
                / assessor_id
                / "RESOURCES"
                / resource_label,
            ]
            for candidate in candidates:
                if candidate.exists():
                    cache[cache_key] = candidate
                    return candidate

        direct_session = project_dir / session_label / "RESOURCES" / resource_label
        if direct_session.exists():
            cache[cache_key] = direct_session
            return direct_session

        direct_session_lower = project_dir / session_label / "resources" / resource_label
        if direct_session_lower.exists():
            cache[cache_key] = direct_session_lower
            return direct_session_lower

        # Fallback: project-level RESOURCES directory (no session element).
        for resources_dir_name in ("RESOURCES", "resources", "Resources"):
            candidate = project_dir / resources_dir_name / resource_label
            if candidate.exists():
                cache[cache_key] = candidate
                return candidate

        cache[cache_key] = None
        return None

    def _resolve_catalog_path(
        self,
        resource_row: Dict,
        *,
        project_label: str,
        session_label: str,
        resource_label: str,
        scan_id: Optional[str] = None,
        assessor_id: Optional[str] = None,
    ) -> Optional[Path]:
        uri_value = resource_row.get("URI") or resource_row.get("uri")
        if uri_value:
            catalog_path = Path(uri_value)
            if catalog_path.is_absolute():
                return catalog_path
            if self.data_root:
                return (self.data_root / catalog_path).resolve(strict=False)

        base_dir = self._resource_dir(
            project_label,
            session_label,
            resource_label,
            scan_id=scan_id,
            assessor_id=assessor_id,
        )
        if base_dir:
            candidates = list(base_dir.glob("*catalog.xml"))
            if candidates:
                return candidates[0].resolve(strict=False)

        return None

    def _verify_catalog_paths(self, report: CheckReport, catalog_paths: Set[Path]) -> None:
        stats = report.stats
        for catalog_path in sorted(catalog_paths):
            stats["catalogs_total"] += 1
            try:
                tree = ET.parse(catalog_path)
            except FileNotFoundError:
                stats["catalogs_with_errors"] += 1
                LOG.error("Catalog missing: %s", catalog_path)
                report.catalog_errors.append(
                    {"catalog": str(catalog_path), "error": "Catalog file not found"}
                )
                continue
            except PermissionError as exc:
                stats["catalogs_with_errors"] += 1
                LOG.error("Catalog access denied: %s (%s)", catalog_path, exc)
                report.catalog_errors.append(
                    {"catalog": str(catalog_path), "error": str(exc)}
                )
                continue
            except (ET.ParseError, OSError) as exc:
                stats["catalogs_with_errors"] += 1
                LOG.error("Failed to parse catalog %s: %s", catalog_path, exc)
                report.catalog_errors.append(
                    {"catalog": str(catalog_path), "error": str(exc)}
                )
                continue

            entries = tree.findall(".//entry")
            if not entries:
                entries = tree.findall(".//{http://nrg.wustl.edu/catalog}entry")

            for entry in entries:
                uri = entry.get("URI") or entry.get("uri")
                if not uri:
                    continue
                stats["catalog_entries_total"] += 1
                candidate = Path(uri)
                if not candidate.is_absolute():
                    candidate = (catalog_path.parent / candidate).resolve(strict=False)
                try:
                    exists = candidate.exists()
                except OSError as exc:
                    stats["catalogs_with_errors"] += 1
                    LOG.error(
                        "Catalog entry access error: %s -> %s (%s)",
                        catalog_path,
                        candidate,
                        exc,
                    )
                    report.catalog_errors.append(
                        {
                            "catalog": str(catalog_path),
                            "error": f"Entry access error: {exc}",
                        }
                    )
                    continue

                if exists:
                    continue
                stats["catalog_entries_missing"] += 1
                LOG.error("Catalog missing entry: %s -> %s", catalog_path, candidate)
                report.catalog_missing_entries.append(
                    {
                        "catalog": str(catalog_path),
                        "entry_uri": uri,
                        "resolved_path": str(candidate),
                    }
                )


def configure_logging(level: str) -> None:
    try:
        log_level = getattr(logging, level.upper())
    except AttributeError:
        log_level = logging.INFO
        LOG.warning("Invalid log level %s, defaulting to INFO", level)
    logging.basicConfig(
        level=log_level,
        format="%(levelname)s %(message)s",
    )


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    configure_logging(args.log_level)

    token = args.api_token or os.environ.get("XNAT_API_TOKEN")
    username = args.username or os.environ.get("XNAT_USERNAME")
    password = args.password or os.environ.get("XNAT_PASSWORD")

    if token:
        LOG.debug("Using token-based authentication")
    elif username:
        if password is None:
            password = getpass.getpass("XNAT password: ")
    else:
        parser.error("Provide --api-token or --username (or set matching environment variables).")

    client = XNATClient(
        args.base_url,
        username=username if not token else None,
        password=password if not token else None,
        token=token,
        verify_ssl=args.verify_ssl,
        timeout=args.timeout,
    )

    include_projects = args.include_projects
    if args.project_id:
        if include_projects:
            parser.error("Use either --project-id or --project, not both.")
        include_projects = [args.project_id]

    checker = FilesystemChecker(
        client,
        data_root=args.data_root,
        path_keys=args.path_keys,
        max_files=args.max_files,
        verify_catalogs=args.verify_catalogs,
        catalog_root=args.catalog_root,
    )

    try:
        report = checker.check(
            include_projects=include_projects,
            skip_projects=args.skip_projects,
        )
    except requests.RequestException as exc:
        LOG.error("Failed during traversal: %s", exc)
        return 1
    except KeyboardInterrupt:
        LOG.warning("Interrupted by user")
        return 130

    stats = report.stats
    LOG.info(
        "Projects: %d | Sessions: %d | Resources: %d | Files checked: %d",
        stats.get("projects", 0),
        stats.get("sessions", 0),
        stats.get("resources", 0),
        stats.get("files_total", 0),
    )
    LOG.info(
        "Files found: %d | Missing: %d | Unresolved: %d",
        stats.get("files_found", 0),
        stats.get("files_missing", 0),
        stats.get("files_unresolved", 0),
    )

    if report.unresolved_files:
        LOG.warning("Unable to resolve %d file paths; adjust --path-key or --data-root.", len(report.unresolved_files))

    if report.missing_files:
        LOG.error("Detected %d missing files.", len(report.missing_files))
        for entry in report.missing_files:
            LOG.error(
                "Missing file: project=%s session=%s resource=%s path=%s",
                entry["project"],
                entry["session"],
                entry["resource"],
                entry["path"],
            )
        if args.fail_on_missing:
            return 2

    report_outputs: List[Tuple[str, Path]] = []
    if args.report_file:
        report_outputs.append((args.report_format, args.report_file))
    if args.report:
        for spec in args.report:
            try:
                fmt, path_spec = spec.split(":", 1)
            except ValueError:
                parser.error(f"Invalid --report value '{spec}'. Expected format: json:/path/to/file")
            fmt = fmt.lower().strip()
            if fmt not in {"json", "text", "html"}:
                parser.error(f"Unsupported report format '{fmt}' in --report.")
            report_outputs.append((fmt, Path(path_spec)))

    if not report_outputs:
        default_stem = _default_report_stem(include_projects)
        report_outputs.extend(
            [
                ("json", default_stem.with_suffix(".json")),
                ("html", default_stem.with_suffix(".html")),
            ]
        )

    for fmt, path in report_outputs:
        try:
            _write_report(report, path, fmt)
        except OSError as exc:
            LOG.error("Failed to write report to %s: %s", path, exc)
            return 3

    resource_report_path: Optional[Path]
    resource_report_format: str
    if args.resource_report_file:
        resource_report_path = args.resource_report_file
        resource_report_format = args.resource_report_format
    else:
        default_stem = _default_report_stem(include_projects)
        resource_report_path = default_stem.with_name(default_stem.name.replace("_report", "_resources")).with_suffix(".csv")
        resource_report_format = "csv"

    try:
        _write_resource_report(report.resource_details, resource_report_path, resource_report_format)
    except OSError as exc:
        LOG.error(
            "Failed to write resource detail report to %s: %s",
            resource_report_path,
            exc,
        )
        return 4

    return 0


def _write_report(report: CheckReport, path: Path, fmt: str) -> None:
    path = path.expanduser()
    path.parent.mkdir(parents=True, exist_ok=True)
    if fmt == "json":
        path.write_text(json.dumps(report.to_dict(), indent=2), encoding="utf-8")
    elif fmt == "text":
        path.write_text(report.to_text() + "\n", encoding="utf-8")
    elif fmt == "html":
        path.write_text(report.to_html(), encoding="utf-8")
    else:  # pragma: no cover - defensive
        raise ValueError(f"Unsupported report format: {fmt}")


def _write_resource_report(
    resources: List[Dict[str, Any]],
    path: Path,
    fmt: str,
) -> None:
    path = path.expanduser()
    path.parent.mkdir(parents=True, exist_ok=True)
    if fmt == "json":
        path.write_text(json.dumps(resources, indent=2), encoding="utf-8")
        return

    if fmt == "csv":
        fieldnames = [
            "project",
            "session",
            "scope",
            "resource",
            "scan",
            "assessor",
            "status",
            "files_listed",
            "error",
            "resource_uri",
            "catalog_path",
        ]
        with path.open("w", newline="", encoding="utf-8") as handle:
            writer = csv.DictWriter(handle, fieldnames=fieldnames)
            writer.writeheader()
            for entry in resources:
                row = {name: entry.get(name, "") for name in fieldnames}
                writer.writerow(row)
        return

    raise ValueError(f"Unsupported resource report format: {fmt}")  # pragma: no cover


def _slugify(value: str) -> str:
    slug = "".join(ch.lower() if ch.isalnum() else "-" for ch in value)
    slug = slug.strip("-")
    while "--" in slug:
        slug = slug.replace("--", "-")
    return slug or "all-projects"


def _default_report_stem(projects: Optional[Sequence[str]]) -> Path:
    if projects:
        slug = _slugify("-".join(sorted(set(projects))))
    else:
        slug = "all-projects"
    return Path(f"xnat_{slug}_report")


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
