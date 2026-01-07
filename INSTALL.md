# Installation Guide

This guide provides detailed instructions for installing and configuring the XNAT Filesystem Check Plugin.

## Prerequisites

### System Requirements

- **XNAT**: Version 1.8.9 or later
- **Java**: Version 11 or later
- **Gradle**: Version 7.x or later (included via wrapper)
- **Node.js**: Version 16.x or later
- **npm**: Version 8.x or later

### Permissions

- Administrator access to XNAT instance
- Write access to XNAT plugins directory
- Ability to restart XNAT/Tomcat

## Installation Steps

### 1. Download or Clone Repository

```bash
git clone https://github.com/your-org/xnat-filesystem-check.git
cd xnat-filesystem-check
```

Or download and extract the release package:

```bash
wget https://github.com/your-org/xnat-filesystem-check/releases/latest/download/xnat-filesystem-check.tar.gz
tar -xzf xnat-filesystem-check.tar.gz
cd xnat-filesystem-check
```

### 2. Build the Plugin

#### Using Gradle Wrapper (Recommended)

```bash
./gradlew clean jar
```

#### Using System Gradle

```bash
gradle clean jar
```

The build process will:
1. Compile Java sources
2. Install npm dependencies
3. Build React frontend
4. Package everything into a JAR file

Build output:
```
build/libs/xnat-filesystem-check-plugin-1.0.0-SNAPSHOT.jar
```

### 3. Deploy to XNAT

#### Option A: Copy to Plugins Directory

```bash
# For standard XNAT installation
sudo cp build/libs/xnat-filesystem-check-plugin-1.0.0-SNAPSHOT.jar \
  /var/lib/tomcat9/webapps/xnat/WEB-INF/plugins/

# For Docker-based XNAT
docker cp build/libs/xnat-filesystem-check-plugin-1.0.0-SNAPSHOT.jar \
  xnat-web:/data/xnat/home/plugins/
```

#### Option B: Use XNAT Plugin Installer

1. Log into XNAT as administrator
2. Navigate to **Administer → Plugin Settings**
3. Click **Upload Plugin**
4. Select the JAR file
5. Click **Install**

### 4. Restart XNAT

#### For Tomcat-based Installation

```bash
sudo systemctl restart tomcat9
```

Or:

```bash
sudo service tomcat9 restart
```

#### For Docker-based Installation

```bash
docker restart xnat-web
```

Or using docker-compose:

```bash
docker-compose restart xnat-web
```

### 5. Verify Installation

1. **Check Plugin is Loaded**:
   - Log into XNAT as administrator
   - Go to **Administer → Plugin Settings**
   - Look for "XNAT Filesystem Check Plugin" in the list
   - Status should show "Enabled" or "Active"

2. **Check Logs**:
   ```bash
   # For Tomcat
   tail -f /var/log/tomcat9/catalina.out | grep FilesystemCheck

   # For Docker
   docker logs -f xnat-web | grep FilesystemCheck
   ```

   You should see:
   ```
   INFO - Initializing XNAT Filesystem Check Plugin
   ```

3. **Access the UI**:
   - Navigate to **Administer → Filesystem Check**
   - You should see the filesystem check interface

4. **Test API**:
   ```bash
   curl -X GET "https://your-xnat.com/xapi/filesystem-check/status" \
     -H "Authorization: Bearer YOUR_TOKEN"
   ```

   Expected response:
   ```json
   {
     "service": "filesystem-check",
     "status": "available",
     "version": "1.0.0"
   }
   ```

## Configuration

### XNAT Site Settings

Ensure your XNAT site settings are correctly configured:

1. Go to **Administer → Site Administration → Site Settings**
2. Verify **Archive Path** is set correctly
   - Example: `/data/xnat/archive`
3. Ensure filesystem is accessible from XNAT server

### Plugin Configuration (Optional)

The plugin uses XNAT's standard configuration. No additional configuration files are required.

### Frontend Access

The UI is automatically registered with XNAT. Access it from:
- **Administer → Filesystem Check** (site admin menu)

## Python CLI Tool Setup

The standalone Python tool can be used independently:

### 1. Install Dependencies

```bash
cd scripts
pip install requests
```

Or create a virtual environment:

```bash
cd scripts
python -m venv venv
source venv/bin/activate  # On Windows: venv\Scripts\activate
pip install requests
```

### 2. Set Environment Variables (Optional)

```bash
export XNAT_USERNAME=your_username
export XNAT_PASSWORD=your_password
# Or use API token
export XNAT_API_TOKEN=your_token
```

### 3. Test CLI Tool

```bash
python xnat_fs_check.py --help
```

## Upgrading

### From Previous Version

1. Stop XNAT/Tomcat
2. Remove old plugin JAR:
   ```bash
   rm /var/lib/tomcat9/webapps/xnat/WEB-INF/plugins/xnat-filesystem-check-plugin-*.jar
   ```
3. Copy new plugin JAR
4. Restart XNAT/Tomcat

### Preserve Settings

Plugin settings are stored in XNAT's database and will be preserved during upgrades.

## Uninstallation

### Remove Plugin

1. Stop XNAT/Tomcat
2. Delete plugin JAR:
   ```bash
   rm /var/lib/tomcat9/webapps/xnat/WEB-INF/plugins/xnat-filesystem-check-plugin-*.jar
   ```
3. Restart XNAT/Tomcat

### Clean Database (Optional)

Plugin does not create database tables, so no cleanup is required.

## Troubleshooting

### Build Fails

**Issue**: Gradle build fails with "Could not resolve dependencies"

**Solution**:
```bash
./gradlew clean build --refresh-dependencies
```

**Issue**: Node/npm not found during build

**Solution**:
- Ensure Node.js 16.x is installed
- Or disable npm build temporarily:
  ```bash
  ./gradlew jar -x npmInstall -x npmBuild
  ```

### Plugin Not Loading

**Issue**: Plugin JAR not recognized by XNAT

**Solution**:
1. Verify JAR is in correct directory
2. Check file permissions:
   ```bash
   ls -la /var/lib/tomcat9/webapps/xnat/WEB-INF/plugins/
   ```
3. Check XNAT logs for errors
4. Ensure XNAT version compatibility

### Permission Errors

**Issue**: "Access denied" when running checks

**Solution**:
- Ensure you're logged in as administrator
- Check user has project access
- Verify filesystem permissions

### Frontend Not Loading

**Issue**: UI shows blank page or errors

**Solution**:
1. Check browser console for JavaScript errors
2. Verify static resources are included in JAR:
   ```bash
   jar tf build/libs/xnat-filesystem-check-plugin-1.0.0-SNAPSHOT.jar | grep xnat-filesystem-check.js
   ```
3. Rebuild with npm build enabled

## Docker Deployment

### Docker Compose Example

```yaml
version: '3.8'
services:
  xnat-web:
    image: xnat/xnat-web:latest
    volumes:
      - ./plugins:/data/xnat/home/plugins
      - ./archive:/data/xnat/archive
    environment:
      - CATALINA_OPTS=-Xms512m -Xmx2g
    ports:
      - "8080:8080"
```

Place plugin JAR in `./plugins/` directory and restart:

```bash
docker-compose down
docker-compose up -d
```

## Production Considerations

### Performance

- Run checks during off-peak hours
- Use `maxFiles` parameter for initial testing
- Monitor server resources during full archive scans

### Security

- Ensure proper XNAT authentication
- Use API tokens instead of passwords
- Restrict access to administrator role
- Review filesystem permissions

### Backup

- Always backup XNAT database before major updates
- Consider backing up plugin settings
- Test in staging environment first

## Getting Help

If you encounter issues:

1. Check logs for error messages
2. Review this troubleshooting guide
3. Search existing GitHub issues
4. Open a new issue with:
   - XNAT version
   - Plugin version
   - Error messages
   - Steps to reproduce

## Next Steps

After installation:
1. Read the [README.md](README.md) for usage instructions
2. Test with a small project first
3. Configure monitoring and alerting
4. Set up regular scheduled checks
