# XNAT Filesystem Check Plugin

A comprehensive XNAT plugin for validating filesystem integrity by checking if files referenced in the XNAT database exist on the filesystem. This plugin ensures data consistency between XNAT's catalog and the physical archive.

## Purpose

**Validates that the XNAT database matches the actual filesystem** by checking:
- ✅ Files referenced in XNAT exist on disk
- ✅ File paths can be resolved correctly
- ✅ Catalog.xml files match actual files
- ❌ Identifies missing files
- ❌ Identifies unresolvable file references

This is critical for:
- Data integrity verification
- Migration validation
- Archive maintenance
- Disaster recovery planning

---

## How It Works

### 1. **Database-to-Filesystem Validation**

The plugin traverses the XNAT database hierarchy and validates each file reference:

```
XNAT Database                    Filesystem Check
─────────────                   ──────────────────
Project(s)                  →   Verify project directory exists
  └─ Session(s)             →   Verify session directory exists
      ├─ Resources          →   Check session-level files
      ├─ Scan(s)            →   Verify scan directories
      │   └─ Resources      →   Check scan files (DICOM, NIFTI, etc.)
      └─ Assessor(s)        →   Verify assessor directories
          └─ Resources      →   Check assessor output files
```

### 2. **What Gets Checked**

#### Session-Level Resources
Files attached directly to sessions (e.g., documents, QC reports):
- Path: `/archive/{project}/arc{N}/{session}/RESOURCES/{resource}/`
- Checks: File existence, path resolution, catalog verification

#### Scan Resources
DICOM and imaging data files:
- Path: `/archive/{project}/arc{N}/{session}/SCANS/{scan_id}/{resource}/`
- Checks: DICOM files, NIFTI conversions, snapshot images, metadata

#### Assessor Resources
Analysis and processing outputs:
- Path: `/archive/{project}/arc{N}/{session}/ASSESSORS/{assessor_id}/{resource}/`
- Checks: FreeSurfer outputs, pipeline results, QC assessments

#### Catalog Files
XML catalogs that index file metadata:
- File: `catalog.xml` in each resource directory
- Checks: XML validity, file entries match actual files

### 3. **Path Resolution Strategy**

The plugin uses multiple strategies to locate files:

1. **Absolute Paths**: If file URI is absolute, check directly
2. **Relative to Archive**: Resolve relative to XNAT archive root
3. **Standard XNAT Layout**: Construct path using project/session/resource structure
4. **Arc Directory Search**: Find appropriate arc### directory under project
5. **Fallback Strategies**: Check alternative naming conventions (RESOURCES vs resources)

Example resolution:
```
Database Reference: /data/archive/MY_PROJECT/arc001/SESSION_001/SCANS/1/DICOM/image.dcm
Resolution Steps:
1. Check if absolute path exists
2. If not, check: {archive_root}/MY_PROJECT/arc001/SESSION_001/SCANS/1/DICOM/image.dcm
3. If not, check: {archive_root}/MY_PROJECT/arc001/SESSION_001/SCANS/1/RESOURCES/DICOM/image.dcm
4. Report as missing if all strategies fail
```

### 4. **Results and Reporting**

The plugin generates comprehensive reports showing:

#### Summary Statistics
- **Projects**: Number of projects checked
- **Sessions**: Total sessions validated
- **Resources**: Total resources examined
- **Files Checked**: Total file references validated
- **Files Found**: Files successfully located ✅
- **Files Missing**: Files referenced in DB but not on disk ❌
- **Files Unresolved**: File references that couldn't be mapped to paths ⚠️

#### Missing Files Report
For each missing file:
```json
{
  "project": "PROJECT_ID",
  "session": "SESSION_LABEL",
  "resource": "RESOURCE_NAME",
  "scope": "scan|session|assessor",
  "scan": "SCAN_ID",
  "path": "/expected/path/to/file.dcm",
  "status": "missing"
}
```

#### Unresolved Files Report
Files that couldn't be mapped to filesystem paths:
```json
{
  "project": "PROJECT_ID",
  "session": "SESSION_LABEL",
  "resource": "RESOURCE_NAME",
  "file": "filename.ext",
  "status": "unresolved",
  "error": "Unable to resolve path from URI"
}
```

#### Resource Details
Per-resource breakdown:
```json
{
  "project": "PROJECT_ID",
  "session": "SESSION_LABEL",
  "resource": "DICOM",
  "scope": "scan",
  "status": "ok",
  "filesListed": 150,
  "filesFound": 148,
  "filesMissing": 2,
  "filesUnresolved": 0,
  "catalogPath": "/archive/.../catalog.xml"
}
```

---

## Features

- **Web-based UI**: Modern React interface integrated into XNAT for easy access
- **Per-project or Archive-wide Checks**: Validate individual projects or scan the entire archive
- **Detailed Reporting**: Generate comprehensive reports in JSON, HTML, and CSV formats
- **Real-time Progress**: Monitor check progress through the web interface
- **Resource Tracking**: Detailed breakdown by sessions, scans, and assessors
- **Missing File Detection**: Identify files referenced in XNAT but missing on disk
- **Catalog Verification**: Optional verification of catalog.xml files
- **REST API**: Full REST API for programmatic access and automation

---

## What It Checks vs. What It Doesn't Check

### ✅ What It Checks

1. **File Existence**: Does each file referenced in XNAT exist on the filesystem?
2. **Path Resolution**: Can file URIs be correctly mapped to filesystem paths?
3. **Resource Integrity**: Are all files listed in a resource present?
4. **Catalog Consistency**: Do catalog.xml entries match actual files? (optional)
5. **Access Permissions**: Can XNAT read the file locations?

### ❌ What It Doesn't Check (Yet - See TODOs)

1. **File Content Integrity**: Not validating file checksums or content
2. **DICOM Validity**: Not parsing/validating DICOM file structure
3. **Orphaned Files**: Not identifying files on disk not referenced in XNAT
4. **Disk Space**: Not checking available storage or quotas
5. **Duplicate Files**: Not detecting duplicate file content
6. **File Permissions**: Not validating read/write permissions in detail
7. **Symbolic Links**: Not following or validating symlinks
8. **Archive Compression**: Not checking compressed archives (ZIP, TAR)

---

## TODO: Future Improvements

### High Priority

- [ ] **Orphaned File Detection**: Scan filesystem and identify files NOT referenced in XNAT database
  - Reverse check: Find files on disk that aren't cataloged in XNAT
  - Report size and age of orphaned files
  - Suggest cleanup actions

- [ ] **Checksum Validation**: Verify file integrity using checksums
  - Compare stored checksums (if available) with actual file checksums
  - Detect corrupted files even if they exist
  - Generate checksums for files that don't have them

- [ ] **DICOM Header Validation**: Parse and validate DICOM files
  - Check DICOM file structure
  - Validate required DICOM tags
  - Verify PatientID, StudyInstanceUID consistency
  - Detect DICOM format corruption

- [ ] **Size Discrepancy Detection**: Compare expected vs actual file sizes
  - Validate file size matches database records
  - Identify truncated or incomplete files
  - Report unusually small/large files

### Medium Priority

- [ ] **Automatic Repair Options**: Attempt to fix common issues
  - Rebuild catalog.xml from actual files
  - Update incorrect paths in database
  - Re-index missing resources

- [ ] **Scheduled Checks**: Background checking capabilities
  - Cron-style scheduled validation
  - Incremental checks (only recent changes)
  - Email notifications of issues

- [ ] **Performance Optimization**: Handle very large archives
  - Parallel file checking
  - Database query optimization
  - Incremental reporting
  - Resume capability for interrupted checks

- [ ] **Export/Import Validation**: Pre/post migration checks
  - Validate before export
  - Generate manifest for transfer
  - Validate after import
  - Diff reports between source and destination

### Low Priority

- [ ] **Compression Support**: Handle archived/compressed resources
  - Check files inside ZIP/TAR archives
  - Validate archive integrity
  - Report on compression ratios

- [ ] **Symbolic Link Handling**: Follow and validate symlinks
  - Detect broken symlinks
  - Validate link targets exist
  - Report circular link references

- [ ] **Historical Tracking**: Track changes over time
  - Store check results in database
  - Trend analysis of issues
  - Alert on new problems

- [ ] **Integration with XNAT Prearchive**: Validate prearchive files
  - Check files before archiving
  - Prevent archiving incomplete sessions

- [ ] **Custom Validation Rules**: User-defined validation logic
  - Plugin extension points
  - Custom validators per project
  - Flexible rule configuration

---

## Components

### 1. XNAT Plugin (Web Interface)

A complete XNAT plugin with:
- Java REST API endpoints
- React-based web UI
- Service layer for filesystem validation
- Integration with XNAT's security and permissions system

### 2. Command-line Tool

A standalone Python script for filesystem checking:
- Located in `scripts/xnat_fs_check.py`
- Can be run independently of XNAT
- Supports multiple output formats
- Detailed logging and error reporting

---

## Installation

### Prerequisites

- XNAT 1.8.11 or later
- Java 11 or later
- Node.js 16.x or later (for building the frontend)
- Gradle 7.x or later

### Building the Plugin

1. Clone this repository:
   ```bash
   git clone <repository-url>
   cd xnat_filesytem_check
   ```

2. Build the plugin:
   ```bash
   ./gradlew clean jar
   ```

3. The plugin JAR will be created at:
   ```
   build/libs/xnat-filesystem-check-plugin-1.0.0-SNAPSHOT.jar
   ```

### Deploying to XNAT

1. Copy the JAR file to your XNAT plugins directory:
   ```bash
   cp build/libs/xnat-filesystem-check-plugin-1.0.0-SNAPSHOT.jar \
      /path/to/xnat/plugins/
   ```

2. Restart your XNAT instance:
   ```bash
   docker restart xnat-web
   # or
   sudo systemctl restart tomcat9
   ```

3. Verify the plugin is loaded:
   - Log into XNAT as an administrator
   - Navigate to Administer → Plugin Settings
   - Look for "XNAT Filesystem Check Plugin" in the list

---

## Usage

### Web Interface

1. **Access the Interface**:
   - Log into XNAT as an administrator
   - Navigate to Administer → Filesystem Check

2. **Select Projects**:
   - Choose specific projects to check, or
   - Select "Check Entire Archive" to scan all accessible projects

3. **Configure Options**:
   - Set maximum files to check (optional, for testing)
   - Enable catalog verification if needed

4. **Run Check**:
   - Click "Run Filesystem Check"
   - Monitor progress in real-time
   - View detailed results in multiple tabs

5. **Download Reports**:
   - Export results as JSON or CSV
   - Share reports with team members

### REST API

#### Check Specific Project

```bash
curl -X POST "https://xnat.example.com/xapi/filesystem-check/check/project/MY_PROJECT" \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "maxFiles": 1000,
    "verifyCatalogs": false
  }'
```

#### Check Entire Archive

```bash
curl -X POST "https://xnat.example.com/xapi/filesystem-check/check/archive" \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "verifyCatalogs": true
  }'
```

#### Check Multiple Projects

```bash
curl -X POST "https://xnat.example.com/xapi/filesystem-check/check" \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "projectIds": ["PROJECT1", "PROJECT2", "PROJECT3"],
    "entireArchive": false,
    "maxFiles": null,
    "verifyCatalogs": false
  }'
```

### Command-line Tool

The Python script can be used independently:

```bash
cd scripts

# Check a specific project
python xnat_fs_check.py \
  --base-url https://xnat.example.com \
  --username admin \
  --project MY_PROJECT \
  --data-root /data/xnat/archive \
  --report-format html \
  --report-file report.html

# Check entire archive
python xnat_fs_check.py \
  --base-url https://xnat.example.com \
  --api-token YOUR_TOKEN \
  --data-root /data/xnat/archive \
  --report json:full_report.json \
  --resource-report-file resources.csv

# Verify catalogs
python xnat_fs_check.py \
  --base-url https://xnat.example.com \
  --username admin \
  --project MY_PROJECT \
  --data-root /data/xnat/archive \
  --verify-catalogs \
  --catalog-root /data/xnat/archive
```

---

## Understanding the Results

### Interpreting Statistics

- **Projects/Sessions/Scans/Assessors**: Counts of XNAT objects checked
- **Resources**: Number of file collections examined
- **Files Total**: All file references found in XNAT database
- **Files Found** ✅: Files that exist on filesystem at expected location
- **Files Missing** ❌: Files in database but NOT on filesystem - **DATA LOSS**
- **Files Unresolved** ⚠️: Files whose paths couldn't be determined - configuration issue

### Common Scenarios

#### Scenario 1: All Files Found ✅
```
Files Checked: 10,000
Files Found: 10,000
Files Missing: 0
Files Unresolved: 0
```
**Result**: Perfect! Database matches filesystem.

#### Scenario 2: Missing Files ❌
```
Files Checked: 10,000
Files Found: 9,850
Files Missing: 150
Files Unresolved: 0
```
**Result**: 150 files referenced in XNAT don't exist on disk. Investigate:
- Were files deleted manually?
- Was there a failed migration?
- Disk corruption?

#### Scenario 3: Unresolved Files ⚠️
```
Files Checked: 10,000
Files Found: 9,500
Files Missing: 0
Files Unresolved: 500
```
**Result**: Path resolution issues. Check:
- Is archive path configured correctly in XNAT?
- Non-standard directory structure?
- Incorrect URI formats in database?

---

## Troubleshooting

### Plugin Not Loading

1. Check XNAT logs for errors:
   ```bash
   tail -f /var/log/tomcat9/catalina.out
   ```

2. Verify JAR is in plugins directory:
   ```bash
   ls -la /path/to/xnat/plugins/
   ```

3. Ensure correct Java version:
   ```bash
   java -version  # Should be 11 or later
   ```

### Missing Files Reported

1. **Verify archive path configuration** in XNAT site settings
2. **Check filesystem permissions** - can XNAT read the archive?
3. **Review archive structure** - does it follow XNAT conventions?
4. **Check for manual deletions** - were files removed outside XNAT?

### Performance Issues

1. Use `maxFiles` parameter for large archives
2. Run checks during off-peak hours
3. Consider per-project checks instead of full archive
4. Monitor server resources during checks

---

## Contributing

Contributions are welcome! Please:
1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests if applicable
5. Submit a pull request

See [TODO section](#todo-future-improvements) for improvement ideas.

---

## License

This project is licensed under the MIT License.

---

## Support

For issues and questions:
- Open an issue on GitHub
- Contact your XNAT administrator
- Consult XNAT documentation: https://wiki.xnat.org

---

## Changelog

### Version 1.0.0-SNAPSHOT
- Initial release
- Web-based UI with React
- REST API endpoints
- Python CLI tool
- Multi-format reporting
- Catalog verification
- Per-project and archive-wide checks
- Database-to-filesystem validation
- Missing file detection
- Path resolution strategies
