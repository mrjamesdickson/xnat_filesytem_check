/*
 * XNAT Filesystem Check Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
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
import org.nrg.xnat.plugin.filesystemcheck.entities.FileCheckResultEntity;
import org.nrg.xnat.plugin.filesystemcheck.entities.FilesystemCheckEntity;
import org.nrg.xnat.plugin.filesystemcheck.models.*;
import org.nrg.xnat.plugin.filesystemcheck.repositories.FileCheckResultDao;
import org.nrg.xnat.plugin.filesystemcheck.repositories.FilesystemCheckDao;
import org.nrg.xnat.plugin.filesystemcheck.services.AsyncFilesystemCheckService;
import org.nrg.xapi.exceptions.NotFoundException;
import org.nrg.xdat.security.helpers.Permissions;
import org.nrg.xdat.security.helpers.Roles;
import org.springframework.security.access.AccessDeniedException;
import org.nrg.xdat.om.XnatProjectdata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static org.nrg.xdat.security.helpers.AccessLevel.Admin;
import static org.springframework.web.bind.annotation.RequestMethod.GET;
import static org.springframework.web.bind.annotation.RequestMethod.POST;

@Api("XNAT Filesystem Check API")
@XapiRestController
@RequestMapping("/filesystem-check")
@Slf4j
public class FilesystemCheckRestApi extends AbstractXapiRestController {

    private final AsyncFilesystemCheckService asyncFilesystemCheckService;
    private final FilesystemCheckDao checkDao;
    private final FileCheckResultDao resultDao;

    @Autowired
    public FilesystemCheckRestApi(
            final AsyncFilesystemCheckService asyncFilesystemCheckService,
            final FilesystemCheckDao checkDao,
            final FileCheckResultDao resultDao,
            final UserManagementServiceI userManagementService,
            final RoleHolder roleHolder) {
        super(userManagementService, roleHolder);
        this.asyncFilesystemCheckService = asyncFilesystemCheckService;
        this.checkDao = checkDao;
        this.resultDao = resultDao;
    }

    @ApiOperation(value = "Start filesystem check for specified projects or entire archive",
            notes = "Starts an async filesystem check and returns immediately with a check ID",
            response = Map.class)
    @ApiResponses({
            @ApiResponse(code = 200, message = "Check started successfully"),
            @ApiResponse(code = 401, message = "Must be authenticated"),
            @ApiResponse(code = 403, message = "Insufficient permissions"),
            @ApiResponse(code = 500, message = "Unexpected error")
    })
    @XapiRequestMapping(value = "/check", produces = MediaType.APPLICATION_JSON_VALUE, method = POST, restrictTo = Admin)
    public ResponseEntity<Map<String, String>> startCheck(
            @ApiParam(value = "Filesystem check request parameters", required = true)
            @RequestBody FilesystemCheckRequest request) {

        final UserI user = getSessionUser();
        log.info("User {} initiated filesystem check", user.getUsername());

        // Validate project permissions
        if (request.getProjectIds() != null && !request.getProjectIds().isEmpty()) {
            for (String projectId : request.getProjectIds()) {
                try {
                    if (!Permissions.canRead(user, "xnat:projectData/ID", projectId)) {
                        throw new AccessDeniedException("Insufficient permissions for project: " + projectId);
                    }
                } catch (Exception e) {
                    if (e instanceof AccessDeniedException) throw (AccessDeniedException) e;
                    log.error("Error checking permissions for project {}: {}", projectId, e.getMessage());
                    throw new AccessDeniedException("Error checking permissions for project: " + projectId);
                }
            }
        }

        // Create check entity
        String checkId = UUID.randomUUID().toString();
        FilesystemCheckEntity checkEntity = FilesystemCheckEntity.builder()
                .checkId(checkId)
                .username(user.getUsername())
                .status("queued")
                .entireArchive(request.getEntireArchive())
                .projectIds(request.getProjectIds() != null ?
                        String.join(",", request.getProjectIds()) : null)
                .maxFiles(request.getMaxFiles())
                .verifyCatalogs(request.getVerifyCatalogs())
                .build();

        checkDao.create(checkEntity);

        // Log the filesystem check start
        log.info("User {} started filesystem check {}", user.getUsername(), checkId);

        // Start async check
        asyncFilesystemCheckService.performCheckAsync(checkId, request, user);

        Map<String, String> response = new HashMap<>();
        response.put("checkId", checkId);
        response.put("status", "queued");
        response.put("message", "Filesystem check started");

        return ResponseEntity.ok(response);
    }

    @ApiOperation(value = "Start filesystem check for a specific project")
    @XapiRequestMapping(value = "/check/project/{projectId}", produces = MediaType.APPLICATION_JSON_VALUE, method = POST, restrictTo = Admin)
    public ResponseEntity<Map<String, String>> startCheckForProject(
            @ApiParam(value = "Project ID", required = true)
            @PathVariable("projectId") String projectId,
            @ApiParam(value = "Optional parameters")
            @RequestBody(required = false) Map<String, Object> params) throws NotFoundException {

        final UserI user = getSessionUser();

        // Verify project exists and user has access
        try {
            XnatProjectdata project = XnatProjectdata.getXnatProjectdatasById(projectId, user, false);
            if (project == null) {
                throw new NotFoundException("Project not found: " + projectId);
            }
        } catch (Exception e) {
            if (e instanceof NotFoundException) throw (NotFoundException) e;
            throw new RuntimeException("Error accessing project: " + projectId, e);
        }

        try {
            if (!Permissions.canRead(user, "xnat:projectData/ID", projectId)) {
                throw new AccessDeniedException("Insufficient permissions for project: " + projectId);
            }
        } catch (Exception e) {
            if (e instanceof AccessDeniedException) throw (AccessDeniedException) e;
            log.error("Error checking permissions for project {}: {}", projectId, e.getMessage());
            throw new AccessDeniedException("Error checking permissions for project: " + projectId);
        }

        FilesystemCheckRequest request = FilesystemCheckRequest.builder()
                .projectIds(Collections.singletonList(projectId))
                .entireArchive(false)
                .maxFiles(params != null && params.containsKey("maxFiles")
                        ? (Integer) params.get("maxFiles") : null)
                .verifyCatalogs(params != null && params.containsKey("verifyCatalogs")
                        ? (Boolean) params.get("verifyCatalogs") : false)
                .build();

        return startCheck(request);
    }

    @ApiOperation(value = "Start filesystem check for entire archive")
    @XapiRequestMapping(value = "/check/archive", produces = MediaType.APPLICATION_JSON_VALUE, method = POST, restrictTo = Admin)
    public ResponseEntity<Map<String, String>> startCheckForArchive(
            @ApiParam(value = "Optional parameters")
            @RequestBody(required = false) Map<String, Object> params) {

        FilesystemCheckRequest request = FilesystemCheckRequest.builder()
                .entireArchive(true)
                .maxFiles(params != null && params.containsKey("maxFiles")
                        ? (Integer) params.get("maxFiles") : null)
                .verifyCatalogs(params != null && params.containsKey("verifyCatalogs")
                        ? (Boolean) params.get("verifyCatalogs") : false)
                .build();

        return startCheck(request);
    }

    @ApiOperation(value = "Get status of a filesystem check")
    @XapiRequestMapping(value = "/checks/{checkId}", produces = MediaType.APPLICATION_JSON_VALUE, method = GET, restrictTo = AccessLevel.Authenticated)
    public ResponseEntity<FilesystemCheckEntity> getCheckStatus(
            @ApiParam(value = "Check ID", required = true)
            @PathVariable("checkId") String checkId) throws NotFoundException {

        FilesystemCheckEntity check = checkDao.findByCheckId(checkId);
        if (check == null) {
            throw new NotFoundException("Check not found: " + checkId);
        }

        // Verify user has access
        UserI user = getSessionUser();
        if (!check.getUsername().equals(user.getUsername()) && !isAdmin(user)) {
            throw new AccessDeniedException("Access denied");
        }

        return ResponseEntity.ok(check);
    }

    @ApiOperation(value = "Get all active checks")
    @XapiRequestMapping(value = "/checks/active", produces = MediaType.APPLICATION_JSON_VALUE, method = GET, restrictTo = Admin)
    public ResponseEntity<List<FilesystemCheckEntity>> getActiveChecks() {
        return ResponseEntity.ok(checkDao.findActiveChecks());
    }

    @ApiOperation(value = "Get user's checks")
    @XapiRequestMapping(value = "/checks/mine", produces = MediaType.APPLICATION_JSON_VALUE, method = GET, restrictTo = AccessLevel.Authenticated)
    public ResponseEntity<List<FilesystemCheckEntity>> getMyChecks() {
        UserI user = getSessionUser();
        return ResponseEntity.ok(checkDao.findByUsername(user.getUsername()));
    }

    @ApiOperation(value = "Get paginated check results")
    @XapiRequestMapping(value = "/checks/{checkId}/results", produces = MediaType.APPLICATION_JSON_VALUE, method = GET, restrictTo = AccessLevel.Authenticated)
    public ResponseEntity<Map<String, Object>> getCheckResults(
            @ApiParam(value = "Check ID", required = true)
            @PathVariable("checkId") String checkId,
            @ApiParam(value = "Page number (0-indexed)")
            @RequestParam(value = "page", defaultValue = "0") int page,
            @ApiParam(value = "Page size")
            @RequestParam(value = "size", defaultValue = "100") int size,
            @ApiParam(value = "Filter by status (found, missing, unresolved, error)")
            @RequestParam(value = "status", required = false) String status) throws NotFoundException {

        // Verify check exists and user has access
        FilesystemCheckEntity check = checkDao.findByCheckId(checkId);
        if (check == null) {
            throw new NotFoundException("Check not found: " + checkId);
        }

        UserI user = getSessionUser();
        if (!check.getUsername().equals(user.getUsername()) && !isAdmin(user)) {
            throw new AccessDeniedException("Access denied");
        }

        // Get results with pagination
        List<FileCheckResultEntity> results;
        long total;

        if (status != null && !status.isEmpty()) {
            results = resultDao.findByCheckIdAndStatus(checkId, status, page, size);
            total = resultDao.countByCheckIdAndStatus(checkId, status);
        } else {
            results = resultDao.findByCheckId(checkId, page, size);
            total = resultDao.countByCheckId(checkId);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("results", results);
        response.put("page", page);
        response.put("size", size);
        response.put("total", total);
        response.put("totalPages", (total + size - 1) / size);

        return ResponseEntity.ok(response);
    }

    @ApiOperation(value = "Export check results as CSV")
    @XapiRequestMapping(value = "/checks/{checkId}/export/csv", produces = "text/csv", method = GET, restrictTo = AccessLevel.Authenticated)
    public void exportResultsCSV(
            @ApiParam(value = "Check ID", required = true)
            @PathVariable("checkId") String checkId,
            @ApiParam(value = "Filter by status")
            @RequestParam(value = "status", required = false) String status,
            HttpServletResponse response) throws Exception {

        // Verify check exists and user has access
        FilesystemCheckEntity check = checkDao.findByCheckId(checkId);
        if (check == null) {
            throw new NotFoundException("Check not found: " + checkId);
        }

        UserI user = getSessionUser();
        if (!check.getUsername().equals(user.getUsername()) && !isAdmin(user)) {
            throw new AccessDeniedException("Access denied");
        }

        response.setContentType("text/csv");
        response.setHeader("Content-Disposition", "attachment; filename=\"filesystem-check-" + checkId + ".csv\"");

        PrintWriter writer = response.getWriter();

        // Write CSV header
        writer.println("Project,Session,Resource,Scope,Scan,Assessor,File,Path,Status,Error,Checked At");

        // Stream results in batches
        int page = 0;
        int pageSize = 1000;
        List<FileCheckResultEntity> results;

        do {
            if (status != null && !status.isEmpty()) {
                results = resultDao.findByCheckIdAndStatus(checkId, status, page, pageSize);
            } else {
                results = resultDao.findByCheckId(checkId, page, pageSize);
            }

            for (FileCheckResultEntity result : results) {
                writer.printf("\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"%n",
                        csvEscape(result.getProject()),
                        csvEscape(result.getSession()),
                        csvEscape(result.getResource()),
                        csvEscape(result.getScope()),
                        csvEscape(result.getScanId()),
                        csvEscape(result.getAssessorId()),
                        csvEscape(result.getFileName()),
                        csvEscape(result.getFilePath()),
                        csvEscape(result.getStatus()),
                        csvEscape(result.getErrorMessage()),
                        result.getCheckedAt() != null ? result.getCheckedAt().toString() : "");
            }

            page++;
        } while (results.size() == pageSize);

        writer.flush();
    }

    @ApiOperation(value = "Get check result summary statistics")
    @XapiRequestMapping(value = "/checks/{checkId}/summary", produces = MediaType.APPLICATION_JSON_VALUE, method = GET, restrictTo = AccessLevel.Authenticated)
    public ResponseEntity<Map<String, Object>> getCheckSummary(
            @ApiParam(value = "Check ID", required = true)
            @PathVariable("checkId") String checkId) throws NotFoundException {

        FilesystemCheckEntity check = checkDao.findByCheckId(checkId);
        if (check == null) {
            throw new NotFoundException("Check not found: " + checkId);
        }

        UserI user = getSessionUser();
        if (!check.getUsername().equals(user.getUsername()) && !isAdmin(user)) {
            throw new AccessDeniedException("Access denied");
        }

        Map<String, Object> summary = new HashMap<>();
        summary.put("checkId", check.getCheckId());
        summary.put("status", check.getStatus());
        summary.put("username", check.getUsername());
        summary.put("startedAt", check.getStartedAt());
        summary.put("completedAt", check.getCompletedAt());
        summary.put("totalFiles", check.getTotalFiles());
        summary.put("processedFiles", check.getProcessedFiles());
        summary.put("filesFound", check.getFilesFound());
        summary.put("filesMissing", check.getFilesMissing());
        summary.put("filesUnresolved", check.getFilesUnresolved());
        summary.put("percentComplete", check.getPercentComplete());

        // Get detailed counts by status
        Map<String, Long> statusCounts = new HashMap<>();
        statusCounts.put("found", resultDao.countByCheckIdAndStatus(checkId, "found"));
        statusCounts.put("missing", resultDao.countByCheckIdAndStatus(checkId, "missing"));
        statusCounts.put("unresolved", resultDao.countByCheckIdAndStatus(checkId, "unresolved"));
        statusCounts.put("error", resultDao.countByCheckIdAndStatus(checkId, "error"));

        summary.put("statusCounts", statusCounts);

        return ResponseEntity.ok(summary);
    }

    @ApiOperation(value = "Cancel a running check")
    @XapiRequestMapping(value = "/checks/{checkId}/cancel", produces = MediaType.APPLICATION_JSON_VALUE, method = POST, restrictTo = AccessLevel.Authenticated)
    public ResponseEntity<Map<String, String>> cancelCheck(
            @ApiParam(value = "Check ID", required = true)
            @PathVariable("checkId") String checkId) throws NotFoundException {

        FilesystemCheckEntity check = checkDao.findByCheckId(checkId);
        if (check == null) {
            throw new NotFoundException("Check not found: " + checkId);
        }

        UserI user = getSessionUser();
        if (!check.getUsername().equals(user.getUsername()) && !isAdmin(user)) {
            throw new AccessDeniedException("Access denied");
        }

        if (!"running".equals(check.getStatus()) && !"queued".equals(check.getStatus())) {
            throw new IllegalStateException("Check is not running (status: " + check.getStatus() + ")");
        }

        asyncFilesystemCheckService.cancelCheck(checkId);

        check.setStatus("cancelled");
        check.setCompletedAt(Instant.now());
        checkDao.update(check);

        Map<String, String> response = new HashMap<>();
        response.put("status", "cancelled");
        response.put("message", "Check cancelled successfully");

        return ResponseEntity.ok(response);
    }

    @ApiOperation(value = "Get service status")
    @XapiRequestMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE, method = GET, restrictTo = AccessLevel.Authenticated)
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("service", "filesystem-check");
        status.put("status", "available");
        status.put("version", "1.0.0");
        status.put("activeChecks", checkDao.findActiveChecks().size());

        return ResponseEntity.ok(status);
    }

    private String csvEscape(String value) {
        if (value == null) return "";
        return value.replace("\"", "\"\"");
    }

    private boolean isAdmin(UserI user) {
        try {
            return Roles.isSiteAdmin(user);
        } catch (Exception e) {
            return false;
        }
    }
}
