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
import org.nrg.xnat.plugin.filesystemcheck.models.FilesystemCheckReport;
import org.nrg.xnat.plugin.filesystemcheck.models.FilesystemCheckRequest;
import org.nrg.xnat.plugin.filesystemcheck.services.FilesystemCheckService;
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
@RequestMapping(value = "/filesystem-check")
@Slf4j
public class FilesystemCheckRestApi extends AbstractXapiRestController {

    private final FilesystemCheckService filesystemCheckService;

    @Autowired
    public FilesystemCheckRestApi(
            final FilesystemCheckService filesystemCheckService,
            final UserManagementServiceI userManagementService,
            final RoleHolder roleHolder) {
        super(userManagementService, roleHolder);
        this.filesystemCheckService = filesystemCheckService;
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

        try {
            UserI user = getSessionUser();
            log.info("User {} initiated filesystem check", user.getUsername());

            FilesystemCheckReport report = filesystemCheckService.performCheck(request, user);
            return new ResponseEntity<>(report, HttpStatus.OK);

        } catch (Exception e) {
            log.error("Error performing filesystem check", e);
            FilesystemCheckReport errorReport = FilesystemCheckReport.builder()
                    .status("failed")
                    .message("Error: " + e.getMessage())
                    .build();
            return new ResponseEntity<>(errorReport, HttpStatus.INTERNAL_SERVER_ERROR);
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

        try {
            UserI user = getSessionUser();
            log.info("User {} initiated filesystem check for project {}", user.getUsername(), projectId);

            FilesystemCheckRequest request = FilesystemCheckRequest.builder()
                    .projectIds(Collections.singletonList(projectId))
                    .entireArchive(false)
                    .maxFiles(params != null && params.containsKey("maxFiles")
                            ? (Integer) params.get("maxFiles") : null)
                    .verifyCatalogs(params != null && params.containsKey("verifyCatalogs")
                            ? (Boolean) params.get("verifyCatalogs") : false)
                    .build();

            FilesystemCheckReport report = filesystemCheckService.performCheck(request, user);
            return new ResponseEntity<>(report, HttpStatus.OK);

        } catch (Exception e) {
            log.error("Error performing filesystem check for project " + projectId, e);
            FilesystemCheckReport errorReport = FilesystemCheckReport.builder()
                    .status("failed")
                    .message("Error: " + e.getMessage())
                    .build();
            return new ResponseEntity<>(errorReport, HttpStatus.INTERNAL_SERVER_ERROR);
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
