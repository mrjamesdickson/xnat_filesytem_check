# XNAT Plugin Development Requirements

This document outlines the essential requirements and best practices for developing XNAT plugins, based on the [official XNAT documentation](https://wiki.xnat.org/documentation/xnat-plugin-configurations) and comparison with official plugins like [OHIF Viewer](https://github.com/JamesAPetts/ohif-viewer-XNAT-plugin).

## Table of Contents
- [Plugin Class Requirements](#plugin-class-requirements)
- [Build Configuration](#build-configuration)
- [REST API Implementation](#rest-api-implementation)
- [Frontend Integration](#frontend-integration)
- [Spring Configuration](#spring-configuration)
- [Resource Organization](#resource-organization)
- [Security and Permissions](#security-and-permissions)
- [Testing and Deployment](#testing-and-deployment)

---

## Plugin Class Requirements

### ✅ Required Annotations

```java
@XnatPlugin(
    value = "pluginId",           // Unique plugin identifier
    name = "Plugin Display Name", // Human-readable name
    description = "Description"   // Brief description
)
@ComponentScan({                  // Packages to scan for components
    "org.example.plugin.rest",
    "org.example.plugin.services"
})
@Slf4j                           // Optional: for logging
public class MyPlugin {
    public MyPlugin() {
        log.info("Initializing My Plugin");
    }
}
```

### ❌ Common Mistakes

1. **Don't add `@Configuration`** - The `@XnatPlugin` annotation already includes Spring's `@Configuration` annotation
   ```java
   // ❌ WRONG - Redundant annotation
   @XnatPlugin(...)
   @Configuration  // DON'T DO THIS
   @ComponentScan(...)
   public class MyPlugin { }

   // ✅ CORRECT
   @XnatPlugin(...)
   @ComponentScan(...)
   public class MyPlugin { }
   ```

2. **Don't include `entityPackages` unless you have custom data models**
   ```java
   // ❌ WRONG - No custom entities defined
   @XnatPlugin(
       value = "myPlugin",
       entityPackages = "org.example.plugin"  // Not needed
   )

   // ✅ CORRECT - Only when you have @XnatDataModel classes
   @XnatPlugin(
       value = "myPlugin",
       dataModels = {@XnatDataModel(value = "custom:dataType", ...)}
   )
   ```

3. **Don't create unnecessary beans**
   ```java
   // ❌ NOT RECOMMENDED - Unnecessary version bean
   @Bean
   public String pluginVersion() {
       return "1.0.0";
   }
   ```

### 📖 References
- **@XnatPlugin includes @Configuration**: The [@XnatPlugin annotation itself includes the Spring Framework's @Configuration annotation](https://wiki.xnat.org/documentation/xnat-plugin-configurations), which gives you the ability to configure and initialize services, create event listeners and handlers, retrieve properties from configuration files, launch REST API controllers, and so on.

---

## Build Configuration

### ✅ Required: Gradle Build System

XNAT plugins use Gradle as the build tool. Key requirements:

```gradle
buildscript {
    ext {
        xnatVersion = '1.8.11'  // Use latest stable XNAT version
    }
    repositories {
        mavenCentral()
        maven {
            url 'https://nrgxnat.jfrog.io/nrgxnat/libs-release'
        }
    }
}

plugins {
    id 'java'
    id 'maven-publish'
}

sourceCompatibility = 11
targetCompatibility = 11
```

### ✅ Dependencies: Use `compileOnly` for XNAT-provided Libraries

**CRITICAL**: Dependencies provided by XNAT at runtime should be marked as `compileOnly`, not `implementation`:

```gradle
dependencies {
    // ✅ CORRECT - XNAT-provided dependencies
    compileOnly "org.nrg.xnat:web:${xnatVersion}"
    compileOnly "org.nrg.xnat:xnat-data-models:${xnatVersion}"
    compileOnly "org.nrg:prefs:${xnatVersion}"
    compileOnly "org.nrg:framework:${xnatVersion}"
    compileOnly "org.springframework:spring-web"
    compileOnly "org.springframework:spring-webmvc"
    compileOnly "com.fasterxml.jackson.core:jackson-databind"
    compileOnly 'org.slf4j:slf4j-api'
    compileOnly 'javax.servlet:javax.servlet-api'

    // Lombok
    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'

    // Test dependencies
    testImplementation 'junit:junit'
    testImplementation "org.nrg.xnat:web:${xnatVersion}"  // For tests only
}
```

### ❌ Common Mistakes

```gradle
// ❌ WRONG - Don't use implementation for XNAT dependencies
dependencies {
    implementation "org.nrg.xnat:web:${xnatVersion}"  // NO!
    implementation "org.springframework:spring-web:5.3.23"  // NO!
}

// ❌ WRONG - Don't specify Spring versions explicitly
dependencies {
    implementation "org.springframework:spring-web:5.3.23"  // Use XNAT's version
}

// ❌ WRONG - Don't include xnat-data-builder unless you need it
buildscript {
    dependencies {
        classpath "org.nrg.xnat.build:xnat-data-builder:${xnatVersion}"  // Only for custom data models
    }
}
```

### 📦 JAR Manifest

```gradle
jar {
    manifest {
        attributes(
            'Implementation-Title': project.name,
            'Implementation-Version': project.version,
            'XNAT-Plugin-Class': 'org.example.plugin.MyPlugin'  // Full plugin class name
        )
    }
}
```

---

## REST API Implementation

### ✅ Required Annotations

```java
@Api("My Plugin API")
@XapiRestController     // ✅ XNAT-specific controller annotation
@Slf4j
public class MyRestApi extends AbstractXapiRestController {

    @Autowired
    public MyRestApi(
            final UserManagementServiceI userManagementService,
            final RoleHolder roleHolder) {
        super(userManagementService, roleHolder);
    }

    @XapiRequestMapping(
        value = "/my-endpoint",
        method = POST,
        restrictTo = Admin
    )
    public ResponseEntity<Result> myEndpoint() {
        UserI user = getSessionUser();
        // Implementation
        return ResponseEntity.ok(result);
    }
}
```

### ❌ Common Mistakes

1. **Don't use `@RequestMapping` at class level**
   ```java
   // ❌ WRONG
   @XapiRestController
   @RequestMapping(value = "/my-api")  // DON'T DO THIS
   public class MyRestApi { }

   // ✅ CORRECT
   @XapiRestController
   public class MyRestApi { }
   ```

2. **Don't return custom error responses - use XNAT exceptions**
   ```java
   // ❌ WRONG
   catch (Exception e) {
       return new ResponseEntity<>(errorObject, HttpStatus.INTERNAL_SERVER_ERROR);
   }

   // ✅ CORRECT
   catch (Exception e) {
       throw new InternalServerErrorException("Error: " + e.getMessage(), e);
   }
   ```

### 🔒 Security: Always Validate Permissions

```java
@XapiRequestMapping(
    value = "/check/project/{projectId}",
    method = POST,
    restrictTo = Admin
)
public ResponseEntity<Report> checkProject(@PathVariable String projectId) {
    UserI user = getSessionUser();

    // ✅ Validate project-level permissions
    if (!Permissions.canRead(user, "xnat:projectData/ID", projectId)) {
        throw new ForbiddenException("Insufficient permissions for project: " + projectId);
    }

    // Create audit event
    EventUtils.newEventInstance(
        EventUtils.CATEGORY.DATA,
        EventUtils.TYPE.PROCESS,
        "Filesystem Check",
        "User " + user.getUsername() + " ran check on project " + projectId
    );

    // Implementation
}
```

### 📖 API Path Convention

All XNAT plugin REST APIs are automatically prefixed with `/xapi/`. Your endpoints will be accessible at:
- `/xapi/my-endpoint`
- `/xapi/check/project/{id}`

---

## Frontend Integration

### ✅ Resource Organization

Frontend assets should be organized in the JAR under `META-INF/resources`:

```gradle
jar {
    // Organize static resources by type
    into('META-INF/resources/scripts/plugin-resources/my-plugin') {
        from file("${projectDir}/src/main/webapp/my-ui/dist")
        include '*.js', '*.js.map'
    }

    into('META-INF/resources/styles/plugin-resources/my-plugin') {
        from file("${projectDir}/src/main/webapp/my-ui/dist")
        include '*.css', '*.css.map'
    }
}
```

### ✅ XNAT Spawner Pattern (React/UI Integration)

```javascript
// Register with XNAT spawner system
if (window.XNAT && window.XNAT.spawner) {
    window.XNAT.spawner.register('my-plugin', {
        render: function(containerId) {
            const container = document.getElementById(containerId);
            if (container) {
                ReactDOM.render(<MyApp />, container);
            }
        }
    });
}
```

### ✅ Spawner YAML Configuration

```yaml
# src/main/resources/META-INF/xnat/spawner/my-spawner.yaml
spawner:
  name: my-plugin
  label: My Plugin
  description: Description of my plugin feature
  version: 1.0.0
  contexts:
    - site-admin  # Where in XNAT UI this appears
  panel:
    label: My Plugin Panel
    kind: panel.react
    contentId: my-plugin-app
    singleton: true
```

### 🎨 Frontend Asset Loading

```html
<!-- Velocity template -->
<link rel="stylesheet" href="/scripts/plugin-resources/my-plugin/styles/app.css"/>
<script src="/scripts/plugin-resources/my-plugin/scripts/app.js"></script>
```

---

## Spring Configuration

### ✅ Modern Approach: Java-based Configuration

**Recommended**: Use `@ComponentScan` in your plugin class - no XML needed

```java
@XnatPlugin(...)
@ComponentScan({
    "org.example.plugin.rest",
    "org.example.plugin.services"
})
public class MyPlugin { }
```

### ❌ Avoid: XML Configuration Files

**Not Recommended**: Creating `META-INF/spring/*.xml` files is unnecessary for modern XNAT plugins

```xml
<!-- ❌ NOT NEEDED - Component scanning handled by @ComponentScan -->
<?xml version="1.0" encoding="UTF-8"?>
<beans xmlns="http://www.springframework.org/schema/beans"
       xmlns:context="http://www.springframework.org/schema/context"
       ...>
    <context:component-scan base-package="org.example.plugin"/>
</beans>
```

If you must use XML, keep it minimal:

```xml
<!-- ✅ Minimal XML if needed -->
<?xml version="1.0" encoding="UTF-8"?>
<beans xmlns="http://www.springframework.org/schema/beans"
       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       xsi:schemaLocation="...">
    <!-- Empty or minimal config - @ComponentScan handles the rest -->
</beans>
```

---

## Resource Organization

### ✅ Standard Plugin Structure

```
my-xnat-plugin/
├── build.gradle
├── settings.gradle
├── gradle.properties
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── org/example/plugin/
│   │   │       ├── MyPlugin.java           # @XnatPlugin class
│   │   │       ├── rest/
│   │   │       │   └── MyRestApi.java      # @XapiRestController
│   │   │       ├── services/
│   │   │       │   └── MyService.java      # @Service
│   │   │       └── models/
│   │   │           └── MyModel.java        # DTOs
│   │   ├── resources/
│   │   │   └── META-INF/
│   │   │       ├── xnat/
│   │   │       │   ├── plugin.yaml         # Optional plugin descriptor
│   │   │       │   └── spawner/
│   │   │       │       └── my-spawner.yaml # UI spawner config
│   │   │       └── resources/              # Static assets (added by build)
│   │   └── webapp/                         # Frontend source
│   │       └── my-ui/
│   │           ├── package.json
│   │           ├── webpack.config.js
│   │           └── src/
│   │               ├── index.js
│   │               └── components/
│   └── test/
│       └── java/
└── README.md
```

### 📁 Resource Paths in JAR

After building, your JAR should contain:

```
META-INF/
├── MANIFEST.MF  (with XNAT-Plugin-Class attribute)
├── xnat/
│   ├── plugin.yaml
│   └── spawner/
│       └── my-spawner.yaml
└── resources/
    ├── scripts/plugin-resources/my-plugin/
    │   └── app.js
    └── styles/plugin-resources/my-plugin/
        └── app.css
```

---

## Security and Permissions

### 🔒 Access Levels

Use XNAT's built-in access levels:

```java
import static org.nrg.xdat.security.helpers.AccessLevel.*;

// Public endpoints (authenticated users)
@XapiRequestMapping(value = "/public", method = GET, restrictTo = Authenticated)

// Project members only
@XapiRequestMapping(value = "/project/{id}", method = GET, restrictTo = Member)

// Site administrators only
@XapiRequestMapping(value = "/admin", method = POST, restrictTo = Admin)
```

### ✅ Permission Validation

```java
// Check if user can read a project
if (Permissions.canRead(user, "xnat:projectData/ID", projectId)) {
    // Allow operation
}

// Check if user can edit
if (Permissions.canEdit(user, "xnat:projectData/ID", projectId)) {
    // Allow modification
}

// Check if user can delete
if (Permissions.canDelete(user, "xnat:projectData/ID", projectId)) {
    // Allow deletion
}
```

### 📝 Audit Logging

```java
EventUtils.newEventInstance(
    EventUtils.CATEGORY.DATA,      // Category
    EventUtils.TYPE.PROCESS,       // Type
    "Action Description",          // Action
    "Details about what happened"  // Comment
);
```

---

## Testing and Deployment

### ✅ Building Your Plugin

```bash
# Clean and build
./gradlew clean jar

# Output location
build/libs/my-plugin-1.0.0-SNAPSHOT.jar
```

### ✅ Deploying to XNAT

1. **Copy to plugins directory**:
   ```bash
   cp build/libs/my-plugin-1.0.0-SNAPSHOT.jar /path/to/xnat/plugins/
   ```

2. **Restart XNAT**:
   ```bash
   # Docker
   docker restart xnat-web

   # Tomcat
   sudo systemctl restart tomcat9
   ```

3. **Verify plugin loaded**:
   - Check XNAT logs for: `Initializing My Plugin`
   - Check Administer → Plugin Settings
   - Test REST API: `GET /xapi/my-endpoint/status`

### ✅ Docker Deployment

```yaml
# docker-compose.yml
version: '3.8'
services:
  xnat-web:
    image: xnat/xnat-web:latest
    volumes:
      - ./plugins:/data/xnat/home/plugins
      - ./archive:/data/xnat/archive
    ports:
      - "8080:8080"
```

---

## Quick Reference Checklist

### Plugin Class
- [ ] `@XnatPlugin` annotation with `value`, `name`, `description`
- [ ] `@ComponentScan` for REST and service packages
- [ ] NO `@Configuration` annotation (included in @XnatPlugin)
- [ ] NO `entityPackages` (unless you have custom data models)
- [ ] Constructor with logging initialization

### Build Configuration
- [ ] Gradle build system with XNAT repositories
- [ ] `compileOnly` for all XNAT-provided dependencies
- [ ] NO explicit Spring version declarations
- [ ] JAR manifest with `XNAT-Plugin-Class` attribute
- [ ] XNAT version 1.8.11 or later
- [ ] Java 11 compatibility

### REST API
- [ ] `@XapiRestController` (not `@RestController`)
- [ ] NO `@RequestMapping` at class level
- [ ] `@XapiRequestMapping` with `restrictTo` access level
- [ ] Extends `AbstractXapiRestController`
- [ ] Permission validation for sensitive operations
- [ ] Audit logging for administrative actions
- [ ] XNAT exception types (not custom error responses)

### Frontend
- [ ] Assets organized under `META-INF/resources/`
- [ ] Spawner YAML configuration
- [ ] XNAT.spawner registration in JavaScript
- [ ] Proper resource paths in templates

### Spring Configuration
- [ ] Java-based configuration via `@ComponentScan`
- [ ] NO duplicate XML configuration
- [ ] Services annotated with `@Service`
- [ ] Repositories annotated with `@Repository`

---

## Common Pitfalls to Avoid

1. ❌ Adding `@Configuration` when using `@XnatPlugin`
2. ❌ Using `implementation` instead of `compileOnly` for XNAT dependencies
3. ❌ Using `@RequestMapping` instead of `@XapiRequestMapping`
4. ❌ Specifying explicit Spring or Jackson versions
5. ❌ Creating both Java `@ComponentScan` AND XML component scanning
6. ❌ Including `entityPackages` without custom data models
7. ❌ Returning custom error objects instead of throwing XNAT exceptions
8. ❌ Missing permission validation on administrative endpoints
9. ❌ Hardcoding resource paths instead of using plugin resource structure
10. ❌ Not testing plugin deployment in a clean XNAT instance

---

## Official Resources

- **XNAT Plugin Configurations**: [wiki.xnat.org/documentation/xnat-plugin-configurations](https://wiki.xnat.org/documentation/xnat-plugin-configurations)
- **Creating XNAT Plugin Projects**: [wiki.xnat.org/documentation/creating-an-xnat-plugin-project](https://wiki.xnat.org/documentation/creating-an-xnat-plugin-project)
- **XNAT REST APIs**: [wiki.xnat.org/documentation/creating-swagger-enabled-xnat-rest-apis-in-a-plugin](https://wiki.xnat.org/documentation/xnat-developer-documentation/working-with-xnat-plugins/developing-xnat-plugins/creating-swagger-enabled-xnat-rest-apis-in-a-plugin)
- **NRG XNAT GitHub**: [github.com/NrgXnat](https://github.com/NrgXnat)
- **OHIF Viewer Plugin**: [github.com/JamesAPetts/ohif-viewer-XNAT-plugin](https://github.com/JamesAPetts/ohif-viewer-XNAT-plugin)

---

**Last Updated**: Based on XNAT 1.8.11 (November 2024)
