package org.nrg.xnat.plugin.filesystemcheck.rest;

import io.swagger.annotations.*;
import lombok.extern.slf4j.Slf4j;
import org.nrg.framework.annotations.XapiRestController;
import org.nrg.xapi.rest.AbstractXapiRestController;
import org.nrg.xapi.rest.XapiRequestMapping;
import org.nrg.xdat.security.helpers.AccessLevel;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.plugin.filesystemcheck.models.*;
import org.nrg.xnat.plugin.filesystemcheck.services.FilesystemCheckService;
import org.nrg.xnat.plugin.filesystemcheck.services.ProgressTrackingService;
import org.nrg.xapi.exceptions.InternalServerErrorException;
import org.nrg.xapi.exceptions.NotFoundException;
import org.nrg.xapi.exceptions.ForbiddenException;
import org.nrg.xdat.security.helpers.Permissions;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xft.event.EventUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.nrg.xdat.security.helpers.AccessLevel.Admin;
import static org.springframework.web.bind.annotation.RequestMethod.GET;
import static org.springframework.web.bind.annotation.RequestMethod.POST;

@Api("XNAT Filesystem Check API")
@XapiRestController
@Slf4j
public class FilesystemCheckRestApi extends AbstractXapiRestController {

    private final FilesystemCheckService filesystemCheckService;
    private final ProgressTrackingService progressTrackingService;

    @Autowired
    public FilesystemCheckRestApi(
            final FilesystemCheckService filesystemCheckService,
            final ProgressTrackingService progressTrackingService,
            final UserManagementServiceI userManagementService,
            final RoleHolder roleHolder) {
        super(userManagementService, roleHolder);
        this.filesystemCheckService = filesystemCheckService;
        this.progressTrackingService = progressTrackingService;
    }

    @ApiOperation(value = "Perform filesystem check for specified projects or entire archive",
            notes = "Validates that files referenced in XNAT exist on the filesystem",
            response = FilesystemCheckReport.class)
    @ApiResponses({
            @ApiResponse(code = 200, message = "Check completed successfully"),
            @ApiResponse(code = 401, message = "Must be authenticated to access the XNAT REST API"),
            @ApiResponse(code = 403, message = "Insufficient permissions"),
            @ApiResponse(code = 500, message = "Unexpected error")
    })
    @XapiRequestMapping(value = "/check", produces = MediaType.APPLICATION_JSON_VALUE, method = POST, restrictTo = Admin)
    public ResponseEntity<FilesystemCheckReport> performCheck(
            @ApiParam(value = "Filesystem check request parameters", required = true)
            @RequestBody FilesystemCheckRequest request) {

        final UserI user = getSessionUser();
        log.info("User {} initiated filesystem check", user.getUsername());

        // Validate project permissions
        if (request.getProjectIds() != null && !request.getProjectIds().isEmpty()) {
            for (String projectId : request.getProjectIds()) {
                if (!Permissions.canRead(user, "xnat:projectData/ID", projectId)) {
                    throw new ForbiddenException("Insufficient permissions for project: " + projectId);
                }
            }
        }

        // Create audit event
        EventUtils.newEventInstance(
                EventUtils.CATEGORY.DATA,
                EventUtils.TYPE.PROCESS,
                "Filesystem Check",
                "User " + user.getUsername() + " initiated filesystem check for " +
                        (request.getEntireArchive() ? "entire archive" : request.getProjectIds().size() + " project(s)")
        );

        try {
            FilesystemCheckReport report = filesystemCheckService.performCheck(request, user);
            return ResponseEntity.ok(report);
        } catch (Exception e) {
            log.error("Error performing filesystem check", e);
            throw new InternalServerErrorException("Filesystem check failed: " + e.getMessage(), e);
        }
    }

    @ApiOperation(value = "Perform filesystem check for a specific project",
            notes = "Validates that files referenced in the specified XNAT project exist on the filesystem",
            response = FilesystemCheckReport.class)
    @ApiResponses({
            @ApiResponse(code = 200, message = "Check completed successfully"),
            @ApiResponse(code = 401, message = "Must be authenticated to access the XNAT REST API"),
            @ApiResponse(code = 403, message = "Insufficient permissions"),
            @ApiResponse(code = 404, message = "Project not found"),
            @ApiResponse(code = 500, message = "Unexpected error")
    })
    @XapiRequestMapping(value = "/check/project/{projectId}", produces = MediaType.APPLICATION_JSON_VALUE, method = POST, restrictTo = Admin)
    public ResponseEntity<FilesystemCheckReport> performCheckForProject(
            @ApiParam(value = "Project ID", required = true)
            @PathVariable("projectId") String projectId,
            @ApiParam(value = "Optional parameters")
            @RequestBody(required = false) Map<String, Object> params) {

        final UserI user = getSessionUser();

        // Verify project exists and user has access
        try {
            XnatProjectdata project = XnatProjectdata.getXnatProjectdatasById(projectId, user, false);
            if (project == null) {
                throw new NotFoundException("Project not found: " + projectId);
            }
        } catch (Exception e) {
            if (e instanceof NotFoundException) throw (NotFoundException) e;
            throw new InternalServerErrorException("Error accessing project: " + projectId, e);
        }

        // Validate permissions
        if (!Permissions.canRead(user, "xnat:projectData/ID", projectId)) {
            log.warn("User {} attempted filesystem check on project {} without permissions",
                    user.getUsername(), projectId);
            throw new ForbiddenException("Insufficient permissions for project: " + projectId);
        }

        log.info("User {} initiated filesystem check for project {}", user.getUsername(), projectId);

        // Create audit event
        EventUtils.newEventInstance(
                EventUtils.CATEGORY.DATA,
                EventUtils.TYPE.PROCESS,
                "Filesystem Check",
                "User " + user.getUsername() + " ran filesystem check on project " + projectId
        );

        FilesystemCheckRequest request = FilesystemCheckRequest.builder()
                .projectIds(Collections.singletonList(projectId))
                .entireArchive(false)
                .maxFiles(params != null && params.containsKey("maxFiles")
                        ? (Integer) params.get("maxFiles") : null)
                .verifyCatalogs(params != null && params.containsKey("verifyCatalogs")
                        ? (Boolean) params.get("verifyCatalogs") : false)
                .build();

        try {
            FilesystemCheckReport report = filesystemCheckService.performCheck(request, user);
            return ResponseEntity.ok(report);
        } catch (Exception e) {
            log.error("Error performing filesystem check for project {}", projectId, e);
            throw new InternalServerErrorException("Filesystem check failed for project " + projectId + ": " + e.getMessage(), e);
        }
    }

    @ApiOperation(value = "Get filesystem check status",
            notes = "Returns the status of filesystem check operations",
            response = Map.class)
    @ApiResponses({
            @ApiResponse(code = 200, message = "Status retrieved successfully"),
            @ApiResponse(code = 401, message = "Must be authenticated to access the XNAT REST API"),
            @ApiResponse(code = 500, message = "Unexpected error")
    })
    @XapiRequestMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE, method = GET, restrictTo = AccessLevel.Authenticated)
    public ResponseEntity<Map<String, Object>> getStatus() {
        try {
            Map<String, Object> status = new HashMap<>();
            status.put("service", "filesystem-check");
            status.put("status", "available");
            status.put("version", "1.0.0");

            return new ResponseEntity<>(status, HttpStatus.OK);

        } catch (Exception e) {
            log.error("Error getting status", e);
            Map<String, Object> errorStatus = new HashMap<>();
            errorStatus.put("status", "error");
            errorStatus.put("message", e.getMessage());
            return new ResponseEntity<>(errorStatus, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @ApiOperation(value = "Perform filesystem check for entire archive",
            notes = "Validates that all files referenced in XNAT exist on the filesystem",
            response = FilesystemCheckReport.class)
    @ApiResponses({
            @ApiResponse(code = 200, message = "Check completed successfully"),
            @ApiResponse(code = 401, message = "Must be authenticated to access the XNAT REST API"),
            @ApiResponse(code = 403, message = "Insufficient permissions (admin required)"),
            @ApiResponse(code = 500, message = "Unexpected error")
    })
    @XapiRequestMapping(value = "/check/archive", produces = MediaType.APPLICATION_JSON_VALUE, method = POST, restrictTo = Admin)
    public ResponseEntity<FilesystemCheckReport> performCheckForArchive(
            @ApiParam(value = "Optional parameters")
            @RequestBody(required = false) Map<String, Object> params) {

        try {
            UserI user = getSessionUser();
            log.info("User {} initiated filesystem check for entire archive", user.getUsername());

            FilesystemCheckRequest request = FilesystemCheckRequest.builder()
                    .entireArchive(true)
                    .maxFiles(params != null && params.containsKey("maxFiles")
                            ? (Integer) params.get("maxFiles") : null)
                    .verifyCatalogs(params != null && params.containsKey("verifyCatalogs")
                            ? (Boolean) params.get("verifyCatalogs") : false)
                    .build();

            FilesystemCheckReport report = filesystemCheckService.performCheck(request, user);
            return new ResponseEntity<>(report, HttpStatus.OK);

        } catch (Exception e) {
            log.error("Error performing filesystem check for archive", e);
            FilesystemCheckReport errorReport = FilesystemCheckReport.builder()
                    .status("failed")
                    .message("Error: " + e.getMessage())
                    .build();
            return new ResponseEntity<>(errorReport, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}

    @ApiOperation(value = "Get progress of a specific check",
            notes = "Returns real-time progress for a running or completed check",
            response = CheckProgress.class)
    @XapiRequestMapping(value = "/progress/{checkId}", produces = MediaType.APPLICATION_JSON_VALUE, method = GET, restrictTo = AccessLevel.Authenticated)
    public ResponseEntity<CheckProgress> getCheckProgress(
            @ApiParam(value = "Check ID", required = true)
            @PathVariable("checkId") String checkId) {

        CheckProgress progress = progressTrackingService.getProgress(checkId);
        if (progress == null) {
            throw new NotFoundException("Check not found: " + checkId);
        }

        // Verify user has access to this check
        UserI user = getSessionUser();
        if (!progress.getUsername().equals(user.getUsername()) && !isAdmin(user)) {
            throw new ForbiddenException("You do not have access to this check");
        }

        return ResponseEntity.ok(progress);
    }

    @ApiOperation(value = "Get all active filesystem checks",
            notes = "Returns all currently running checks (admin only)",
            response = Map.class)
    @XapiRequestMapping(value = "/progress/active", produces = MediaType.APPLICATION_JSON_VALUE, method = GET, restrictTo = Admin)
    public ResponseEntity<Map<String, CheckProgress>> getActiveChecks() {
        return ResponseEntity.ok(progressTrackingService.getActiveChecks());
    }

    @ApiOperation(value = "Get user's filesystem checks",
            notes = "Returns all checks (active and completed) for the current user",
            response = Map.class)
    @XapiRequestMapping(value = "/progress/mine", produces = MediaType.APPLICATION_JSON_VALUE, method = GET, restrictTo = AccessLevel.Authenticated)
    public ResponseEntity<Map<String, CheckProgress>> getMyChecks() {
        UserI user = getSessionUser();
        return ResponseEntity.ok(progressTrackingService.getChecksForUser(user.getUsername()));
    }

    @ApiOperation(value = "Cancel a running check",
            notes = "Cancels a running filesystem check")
    @XapiRequestMapping(value = "/progress/{checkId}/cancel", produces = MediaType.APPLICATION_JSON_VALUE, method = POST, restrictTo = AccessLevel.Authenticated)
    public ResponseEntity<Map<String, String>> cancelCheck(
            @ApiParam(value = "Check ID", required = true)
            @PathVariable("checkId") String checkId) {

        CheckProgress progress = progressTrackingService.getProgress(checkId);
        if (progress == null) {
            throw new NotFoundException("Check not found: " + checkId);
        }

        // Verify user has access to cancel this check
        UserI user = getSessionUser();
        if (!progress.getUsername().equals(user.getUsername()) && !isAdmin(user)) {
            throw new ForbiddenException("You do not have permission to cancel this check");
        }

        if (!"running".equals(progress.getStatus())) {
            throw new InternalServerErrorException("Check is not running (status: " + progress.getStatus() + ")");
        }

        progressTrackingService.cancelCheck(checkId);

        Map<String, String> response = new HashMap<>();
        response.put("status", "cancelled");
        response.put("message", "Check cancelled successfully");

        return ResponseEntity.ok(response);
    }

    private boolean isAdmin(UserI user) {
        try {
            return Permissions.isAdmin(user);
        } catch (Exception e) {
            return false;
        }
    }
}
