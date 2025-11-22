# XNAT Filesystem Check Plugin

A comprehensive XNAT plugin for validating filesystem integrity by checking if files referenced in XNAT exist on the filesystem. This plugin provides both a Python command-line tool and a web-based UI integrated directly into XNAT.

## Features

- **Web-based UI**: Modern React interface integrated into XNAT for easy access
- **Per-project or Archive-wide Checks**: Validate individual projects or scan the entire archive
- **Detailed Reporting**: Generate comprehensive reports in JSON, HTML, and CSV formats
- **Real-time Progress**: Monitor check progress through the web interface
- **Resource Tracking**: Detailed breakdown by sessions, scans, and assessors
- **Missing File Detection**: Identify files referenced in XNAT but missing on disk
- **Catalog Verification**: Optional verification of catalog.xml files
- **REST API**: Full REST API for programmatic access and automation

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

## Installation

### Prerequisites

- XNAT 1.8.9 or later
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

#### Get Status

```bash
curl -X GET "https://xnat.example.com/xapi/filesystem-check/status" \
  -H "Authorization: Bearer YOUR_TOKEN"
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

## Report Format

### Summary Statistics

Reports include comprehensive statistics:
- **Project Summary**: Number of projects, sessions, scans, and assessors
- **Filesystem Results**: Files checked, found, missing, and unresolved
- **Resource Breakdown**: Statistics by resource type (session, scan, assessor)

### Missing Files

For each missing file:
- Project and session identifiers
- Resource name and scope
- Expected filesystem path
- Scan or assessor ID (if applicable)

### Unresolved Files

Files that could not be mapped to filesystem paths:
- File metadata from XNAT
- Reason for unresolved status

### Resource Details

Per-resource statistics:
- Files listed in resource
- Files found vs. missing
- Catalog path (if available)
- Error messages (if any)

## Configuration

### Plugin Settings

The plugin respects XNAT's standard configuration:
- Archive path from XNAT site settings
- User permissions and project access
- Security and authentication settings

### Customization

Key configuration options:
- `maxFiles`: Limit number of files to check (useful for testing)
- `verifyCatalogs`: Enable catalog.xml verification
- Path resolution strategies (configurable in service layer)

## Development

### Building Frontend Only

```bash
cd src/main/webapp/xnat-filesystem-check
npm install
npm run build
```

### Development Mode

```bash
cd src/main/webapp/xnat-filesystem-check
npm run dev
```

### Running Tests

```bash
./gradlew test
```

## Architecture

### Backend (Java)

- **REST API** (`FilesystemCheckRestApi.java`): HTTP endpoints
- **Service Layer** (`FilesystemCheckService.java`): Business logic
- **Models** (`models/`): Data transfer objects
- **Plugin Class** (`FilesystemCheckPlugin.java`): Plugin initialization

### Frontend (React)

- **Main App** (`FilesystemCheckApp.jsx`): Primary component
- **Project Selector** (`ProjectSelector.jsx`): Project selection UI
- **Check Options** (`CheckOptions.jsx`): Configuration options
- **Results Display** (`CheckResults.jsx`): Report visualization

### CLI Tool (Python)

- **XNAT Client**: REST API integration
- **Filesystem Checker**: Core validation logic
- **Report Generation**: Multiple output formats
- **Catalog Verification**: XML parsing and validation

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

1. Verify archive path configuration in XNAT
2. Check filesystem permissions
3. Ensure archive structure follows XNAT conventions
4. Review path resolution strategies in service layer

### Performance Issues

1. Use `maxFiles` parameter for large archives
2. Run checks during off-peak hours
3. Consider per-project checks instead of full archive
4. Monitor server resources during checks

## Contributing

Contributions are welcome! Please:
1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests if applicable
5. Submit a pull request

## License

This project is licensed under the MIT License.

## Support

For issues and questions:
- Open an issue on GitHub
- Contact your XNAT administrator
- Consult XNAT documentation: https://wiki.xnat.org

## Changelog

### Version 1.0.0-SNAPSHOT
- Initial release
- Web-based UI with React
- REST API endpoints
- Python CLI tool
- Multi-format reporting
- Catalog verification
- Per-project and archive-wide checks
