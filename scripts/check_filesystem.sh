#!/usr/bin/env bash
#
# XNAT Filesystem Check Plugin
# Copyright (c) 2025 XNATWorks.
# All rights reserved.
#
# Standalone script to check XNAT filesystem integrity without the plugin.
# This script wraps xnat_fs_check.py with sensible defaults.
#
# Usage:
#   ./check_filesystem.sh -u https://xnat.example.com    # Check with URL
#   ./check_filesystem.sh PROJECT_ID                     # Check a specific project
#   ./check_filesystem.sh --help                         # Show all options
#
# Environment variables:
#   XNAT_URL       - XNAT server URL (required if not passed via -u)
#   XNAT_USER      - XNAT username (default: admin)
#   XNAT_PASSWORD  - XNAT password (will prompt if not set)
#   XNAT_DATA_ROOT - Archive data root (default: /data/xnat/archive)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PYTHON_SCRIPT="${SCRIPT_DIR}/xnat_fs_check.py"

# Defaults
XNAT_URL="${XNAT_URL:-}"
XNAT_USER="${XNAT_USER:-admin}"
XNAT_DATA_ROOT="${XNAT_DATA_ROOT:-/data/xnat/archive}"

# Output directory
OUTPUT_DIR="${OUTPUT_DIR:-./reports}"
mkdir -p "${OUTPUT_DIR}"

# Timestamp for report filenames
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

usage() {
    cat << EOF
XNAT Filesystem Check - Standalone Script

Usage:
    $(basename "$0") -u URL [OPTIONS] [PROJECT_ID]

Options:
    -u, --url URL           XNAT server URL (required)
    -U, --user USER         XNAT username (default: admin)
    -p, --password PASS     XNAT password (will prompt if not provided)
    -d, --data-root PATH    Archive data root (default: /data/xnat/archive)
    -o, --output DIR        Output directory for reports (default: ./reports)
    -m, --max-files N       Limit check to N files (useful for testing)
    -v, --verify-catalogs   Also verify catalog.xml files
    --skip-project ID       Skip specific project(s) (can be repeated)
    -h, --help              Show this help message

Arguments:
    PROJECT_ID              Optional project ID to check (checks all if omitted)

Examples:
    # Check all projects
    $(basename "$0") -u https://xnat.example.com

    # Check a specific project
    $(basename "$0") -u https://xnat.example.com MY_PROJECT

    # Check with max 1000 files (quick test)
    $(basename "$0") -u https://xnat.example.com -m 1000 MY_PROJECT

    # Different user
    $(basename "$0") -u https://xnat.example.com -U myuser

Environment Variables:
    XNAT_URL        XNAT server URL (alternative to -u)
    XNAT_USER       XNAT username
    XNAT_PASSWORD   XNAT password
    XNAT_DATA_ROOT  Archive data root path
    OUTPUT_DIR      Report output directory

EOF
}

# Parse arguments
PROJECT_ID=""
MAX_FILES=""
VERIFY_CATALOGS=""
SKIP_PROJECTS=()
PASSWORD="${XNAT_PASSWORD:-}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        -u|--url)
            XNAT_URL="$2"
            shift 2
            ;;
        -U|--user)
            XNAT_USER="$2"
            shift 2
            ;;
        -p|--password)
            PASSWORD="$2"
            shift 2
            ;;
        -d|--data-root)
            XNAT_DATA_ROOT="$2"
            shift 2
            ;;
        -o|--output)
            OUTPUT_DIR="$2"
            mkdir -p "${OUTPUT_DIR}"
            shift 2
            ;;
        -m|--max-files)
            MAX_FILES="$2"
            shift 2
            ;;
        -v|--verify-catalogs)
            VERIFY_CATALOGS="true"
            shift
            ;;
        --skip-project)
            SKIP_PROJECTS+=("$2")
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        -*)
            echo "Unknown option: $1" >&2
            usage
            exit 1
            ;;
        *)
            PROJECT_ID="$1"
            shift
            ;;
    esac
done

# Check required URL
if [[ -z "${XNAT_URL}" ]]; then
    echo "Error: XNAT URL is required. Use -u option or set XNAT_URL environment variable." >&2
    usage
    exit 1
fi

# Prompt for password if not set
if [[ -z "${PASSWORD}" ]]; then
    echo -n "XNAT Password for ${XNAT_USER}@${XNAT_URL}: "
    read -s PASSWORD
    echo
fi

# Build command
CMD=(
    python3 "${PYTHON_SCRIPT}"
    --base-url "${XNAT_URL}"
    --username "${XNAT_USER}"
    --password "${PASSWORD}"
    --data-root "${XNAT_DATA_ROOT}"
)

# Add project filter if specified
if [[ -n "${PROJECT_ID}" ]]; then
    CMD+=(--project "${PROJECT_ID}")
    REPORT_PREFIX="${OUTPUT_DIR}/fscheck_${PROJECT_ID}_${TIMESTAMP}"
else
    REPORT_PREFIX="${OUTPUT_DIR}/fscheck_all_${TIMESTAMP}"
fi

# Add optional flags
if [[ -n "${MAX_FILES}" ]]; then
    CMD+=(--max-files "${MAX_FILES}")
fi

if [[ -n "${VERIFY_CATALOGS}" ]]; then
    CMD+=(--verify-catalogs)
fi

for skip in "${SKIP_PROJECTS[@]}"; do
    CMD+=(--skip-project "${skip}")
done

# Add report outputs
CMD+=(
    --report "json:${REPORT_PREFIX}.json"
    --report "html:${REPORT_PREFIX}.html"
    --resource-report-file "${REPORT_PREFIX}_resources.csv"
    --resource-report-format csv
)

# Show what we're doing
echo "============================================"
echo "XNAT Filesystem Check"
echo "============================================"
echo "Server:      ${XNAT_URL}"
echo "User:        ${XNAT_USER}"
echo "Data Root:   ${XNAT_DATA_ROOT}"
echo "Project:     ${PROJECT_ID:-ALL}"
echo "Output:      ${REPORT_PREFIX}.*"
echo "============================================"
echo

# Run the check
"${CMD[@]}"

EXIT_CODE=$?

echo
echo "============================================"
echo "Check complete!"
echo "============================================"
echo "Reports generated:"
echo "  JSON:      ${REPORT_PREFIX}.json"
echo "  HTML:      ${REPORT_PREFIX}.html"
echo "  Resources: ${REPORT_PREFIX}_resources.csv"
echo

exit ${EXIT_CODE}
