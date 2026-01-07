/*
 * XNAT Filesystem Check Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package org.nrg.xnat.plugin.filesystemcheck.services;

import lombok.extern.slf4j.Slf4j;
import org.nrg.xdat.bean.CatCatalogBean;
import org.nrg.xdat.bean.CatEntryBean;
import org.nrg.xdat.model.XnatAbstractresourceI;
import org.nrg.xdat.model.XnatExperimentdataI;
import org.nrg.xdat.model.XnatImageassessordataI;
import org.nrg.xdat.model.XnatImagescandataI;
import org.nrg.xdat.om.*;
import org.nrg.xdat.security.helpers.Permissions;
import org.nrg.xdat.security.user.exceptions.UserInitException;
import org.nrg.xdat.security.user.exceptions.UserNotFoundException;
import org.nrg.xft.XFTItem;
import org.nrg.xft.event.EventUtils;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.plugin.filesystemcheck.models.*;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service("fsCheckService")
public class FilesystemCheckService {

    private static final String[] PATH_KEYS = {
        "absolutePath", "absolute_path", "cachepath", "cache_path",
        "path", "filepath", "file_path", "relativePath"
    };

    /**
     * Perform filesystem check for specified projects or entire archive
     */
    public FilesystemCheckReport performCheck(FilesystemCheckRequest request, UserI user) {
        log.info("Starting filesystem check - entireArchive: {}, projects: {}",
                request.getEntireArchive(), request.getProjectIds());

        Map<String, Integer> stats = new ConcurrentHashMap<>();
        initializeStats(stats);

        List<FileCheckResult> missingFiles = new ArrayList<>();
        List<FileCheckResult> unresolvedFiles = new ArrayList<>();
        List<ResourceCheckResult> resourceDetails = new ArrayList<>();

        try {
            List<String> projectsToCheck = determineProjects(request, user);

            for (String projectId : projectsToCheck) {
                try {
                    XnatProjectdata project = XnatProjectdata.getXnatProjectdatasById(projectId, user, false);
                    if (project == null) {
                        log.warn("Project not found or no access: {}", projectId);
                        continue;
                    }

                    checkProject(project, user, stats, missingFiles, unresolvedFiles,
                            resourceDetails, request.getMaxFiles());
                    stats.put("projects", stats.get("projects") + 1);

                } catch (Exception e) {
                    log.error("Error checking project {}: {}", projectId, e.getMessage(), e);
                }
            }

            return FilesystemCheckReport.builder()
                    .generatedAt(Instant.now())
                    .stats(stats)
                    .missingFiles(missingFiles)
                    .unresolvedFiles(unresolvedFiles)
                    .resourceDetails(resourceDetails)
                    .status("completed")
                    .build();

        } catch (Exception e) {
            log.error("Filesystem check failed: {}", e.getMessage(), e);
            return FilesystemCheckReport.builder()
                    .generatedAt(Instant.now())
                    .stats(stats)
                    .status("failed")
                    .message("Check failed: " + e.getMessage())
                    .build();
        }
    }

    private void initializeStats(Map<String, Integer> stats) {
        stats.put("projects", 0);
        stats.put("sessions", 0);
        stats.put("scans", 0);
        stats.put("assessors", 0);
        stats.put("resources", 0);
        stats.put("session_resources", 0);
        stats.put("scan_resources", 0);
        stats.put("assessor_resources", 0);
        stats.put("files_total", 0);
        stats.put("files_found", 0);
        stats.put("files_missing", 0);
        stats.put("files_unresolved", 0);
    }

    private List<String> determineProjects(FilesystemCheckRequest request, UserI user)
            throws UserInitException, UserNotFoundException {

        if (request.getEntireArchive() != null && request.getEntireArchive()) {
            // Get all projects user has access to
            List<String> allProjects = new ArrayList<>();
            try {
                ArrayList<XnatProjectdata> projects = XnatProjectdata.getAllXnatProjectdatas(user, false);
                for (XnatProjectdata project : projects) {
                    allProjects.add(project.getId());
                }
            } catch (Exception e) {
                log.error("Error getting all projects: {}", e.getMessage());
            }
            return allProjects;
        } else if (request.getProjectIds() != null && !request.getProjectIds().isEmpty()) {
            return request.getProjectIds();
        }

        return Collections.emptyList();
    }

    private void checkProject(XnatProjectdata project, UserI user,
                              Map<String, Integer> stats,
                              List<FileCheckResult> missingFiles,
                              List<FileCheckResult> unresolvedFiles,
                              List<ResourceCheckResult> resourceDetails,
                              Integer maxFiles) {

        log.info("Checking project: {}", project.getId());
        String archivePath = project.getRootArchivePath();

        // Check all sessions in project
        List<? extends XnatExperimentdataI> sessions = project.getExperiments();
        for (XnatExperimentdataI sessionI : sessions) {
            if (maxFiles != null && stats.get("files_total") >= maxFiles) {
                log.info("Reached max files limit: {}", maxFiles);
                return;
            }

            if (sessionI instanceof XnatExperimentdata) {
                checkSession(project.getId(), (XnatExperimentdata) sessionI, user, stats, missingFiles,
                        unresolvedFiles, resourceDetails, archivePath, maxFiles);
                stats.put("sessions", stats.get("sessions") + 1);
            }
        }
    }

    private void checkSession(String projectId, XnatExperimentdata session, UserI user,
                              Map<String, Integer> stats,
                              List<FileCheckResult> missingFiles,
                              List<FileCheckResult> unresolvedFiles,
                              List<ResourceCheckResult> resourceDetails,
                              String archivePath,
                              Integer maxFiles) {

        String sessionLabel = session.getLabel();
        log.debug("Checking session: {}/{}", projectId, sessionLabel);

        // Check session-level resources
        if (session instanceof XnatImagesessiondata) {
            XnatImagesessiondata imageSession = (XnatImagesessiondata) session;

            // Session resources
            for (XnatAbstractresourceI resourceI : imageSession.getResources_resource()) {
                if (maxFiles != null && stats.get("files_total") >= maxFiles) return;
                if (!(resourceI instanceof XnatAbstractresource)) continue;
                XnatAbstractresource resource = (XnatAbstractresource) resourceI;

                checkResource(projectId, sessionLabel, resource, "session", null, null,
                        stats, missingFiles, unresolvedFiles, resourceDetails,
                        archivePath, maxFiles);
                stats.put("session_resources", stats.get("session_resources") + 1);
            }

            // Scan resources
            for (XnatImagescandataI scanI : imageSession.getScans_scan()) {
                stats.put("scans", stats.get("scans") + 1);
                String scanId = scanI.getId();

                for (XnatAbstractresourceI resourceI : scanI.getFile()) {
                    if (maxFiles != null && stats.get("files_total") >= maxFiles) return;
                    if (!(resourceI instanceof XnatAbstractresource)) continue;
                    XnatAbstractresource resource = (XnatAbstractresource) resourceI;

                    checkResource(projectId, sessionLabel, resource, "scan", scanId, null,
                            stats, missingFiles, unresolvedFiles, resourceDetails,
                            archivePath, maxFiles);
                    stats.put("scan_resources", stats.get("scan_resources") + 1);
                }
            }

            // Assessor resources
            for (XnatImageassessordataI assessorI : imageSession.getAssessors_assessor()) {
                stats.put("assessors", stats.get("assessors") + 1);
                String assessorId = assessorI.getId();

                for (XnatAbstractresourceI resourceI : assessorI.getOut_file()) {
                    if (maxFiles != null && stats.get("files_total") >= maxFiles) return;
                    if (!(resourceI instanceof XnatAbstractresource)) continue;
                    XnatAbstractresource resource = (XnatAbstractresource) resourceI;

                    checkResource(projectId, sessionLabel, resource, "assessor", null, assessorId,
                            stats, missingFiles, unresolvedFiles, resourceDetails,
                            archivePath, maxFiles);
                    stats.put("assessor_resources", stats.get("assessor_resources") + 1);
                }
            }
        }
    }

    private void checkResource(String projectId, String sessionLabel,
                               XnatAbstractresource resource, String scope,
                               String scanId, String assessorId,
                               Map<String, Integer> stats,
                               List<FileCheckResult> missingFiles,
                               List<FileCheckResult> unresolvedFiles,
                               List<ResourceCheckResult> resourceDetails,
                               String archivePath,
                               Integer maxFiles) {

        String resourceLabel = resource.getLabel();
        stats.put("resources", stats.get("resources") + 1);

        ResourceCheckResult.ResourceCheckResultBuilder resourceResult = ResourceCheckResult.builder()
                .project(projectId)
                .session(sessionLabel)
                .resource(resourceLabel)
                .scope(scope)
                .scan(scanId)
                .assessor(assessorId);

        int filesFound = 0;
        int filesMissing = 0;
        int filesUnresolved = 0;
        int filesListed = 0;

        try {
            // Get catalog path
            String catalogPath = getCatalogPath(resource, archivePath, projectId,
                    sessionLabel, resourceLabel, scanId, assessorId);

            if (catalogPath != null) {
                resourceResult.catalogPath(catalogPath);
            }

            // Check files in resource
            if (resource instanceof XnatResourcecatalog) {
                XnatResourcecatalog catalogResource = (XnatResourcecatalog) resource;

                try {
                    // Build resource path for catalog lookup
                    String resourcePath = buildResourcePath(archivePath, projectId, sessionLabel, scanId, assessorId, resourceLabel);
                    CatCatalogBean catalog = catalogResource.getCatalog(resourcePath);

                    if (catalog != null && catalog.getEntries_entry() != null) {
                        for (Object entryObj : catalog.getEntries_entry()) {
                            if (maxFiles != null && stats.get("files_total") >= maxFiles) break;

                            if (entryObj instanceof CatEntryBean) {
                                CatEntryBean entry = (CatEntryBean) entryObj;
                                filesListed++;
                                stats.put("files_total", stats.get("files_total") + 1);

                                FileCheckResult fileResult = checkFile(entry, projectId, sessionLabel,
                                        resourceLabel, scope, scanId, assessorId, resourcePath);

                                if (fileResult != null) {
                                    switch (fileResult.getStatus()) {
                                        case "found":
                                            filesFound++;
                                            stats.put("files_found", stats.get("files_found") + 1);
                                            break;
                                        case "missing":
                                            filesMissing++;
                                            stats.put("files_missing", stats.get("files_missing") + 1);
                                            missingFiles.add(fileResult);
                                            break;
                                        case "unresolved":
                                            filesUnresolved++;
                                            stats.put("files_unresolved", stats.get("files_unresolved") + 1);
                                            unresolvedFiles.add(fileResult);
                                            break;
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("Error reading catalog for resource {}: {}", resourceLabel, e.getMessage());
                }
            }

            resourceResult
                    .status("ok")
                    .filesListed(filesListed)
                    .filesFound(filesFound)
                    .filesMissing(filesMissing)
                    .filesUnresolved(filesUnresolved);

        } catch (Exception e) {
            log.error("Error checking resource {}/{}/{}: {}",
                    projectId, sessionLabel, resourceLabel, e.getMessage());
            resourceResult
                    .status("error")
                    .error(e.getMessage());
        }

        resourceDetails.add(resourceResult.build());
    }

    private String buildResourcePath(String archivePath, String projectId, String sessionLabel,
                                      String scanId, String assessorId, String resourceLabel) {
        StringBuilder path = new StringBuilder();
        if (archivePath != null) {
            path.append(archivePath);
            if (!archivePath.endsWith("/")) {
                path.append("/");
            }
        }

        // Try to find arc directory
        File projectDir = new File(path.toString() + projectId);
        if (projectDir.exists()) {
            File[] arcDirs = projectDir.listFiles(f -> f.isDirectory() && f.getName().startsWith("arc"));
            if (arcDirs != null && arcDirs.length > 0) {
                path.append(projectId).append("/").append(arcDirs[0].getName()).append("/");
            } else {
                path.append(projectId).append("/");
            }
        }

        path.append(sessionLabel).append("/");

        if (scanId != null) {
            path.append("SCANS/").append(scanId).append("/");
        } else if (assessorId != null) {
            path.append("ASSESSORS/").append(assessorId).append("/");
        } else {
            path.append("RESOURCES/");
        }

        path.append(resourceLabel).append("/");
        return path.toString();
    }

    private FileCheckResult checkFile(CatEntryBean entry,
                                      String projectId, String sessionLabel,
                                      String resourceLabel, String scope,
                                      String scanId, String assessorId,
                                      String resourcePath) {

        String fileName = entry.getName();
        String uri = entry.getUri();

        FileCheckResult.FileCheckResultBuilder result = FileCheckResult.builder()
                .project(projectId)
                .session(sessionLabel)
                .resource(resourceLabel)
                .scope(scope)
                .scan(scanId)
                .assessor(assessorId)
                .file(fileName);

        try {
            Path filePath = resolveFilePath(uri, resourcePath);

            if (filePath == null) {
                log.warn("Unable to resolve path for file: {}", fileName);
                return result.status("unresolved").build();
            }

            result.path(filePath.toString());

            if (Files.exists(filePath)) {
                log.debug("File exists: {}", filePath);
                return result.status("found").build();
            } else {
                log.warn("File missing: {}", filePath);
                return result.status("missing").build();
            }

        } catch (Exception e) {
            log.error("Error checking file {}: {}", fileName, e.getMessage());
            return result.status("error").error(e.getMessage()).build();
        }
    }

    private Path resolveFilePath(String uri, String resourcePath) {

        // Strategy 1: Check if URI is an absolute path
        if (uri != null && uri.startsWith("/")) {
            Path absPath = Paths.get(uri);
            if (Files.exists(absPath)) {
                return absPath;
            }
        }

        // Strategy 2: Resolve relative to resource path
        if (resourcePath != null && uri != null) {
            String relativePath = uri;
            if (relativePath.contains("/files/")) {
                relativePath = relativePath.substring(relativePath.indexOf("/files/") + 7);
            }

            Path resolved = Paths.get(resourcePath, relativePath);
            if (Files.exists(resolved)) {
                return resolved;
            }

            // Also try without leading path segments
            Path directPath = Paths.get(resourcePath, uri);
            if (Files.exists(directPath)) {
                return directPath;
            }
        }

        return null;
    }

    private String getCatalogPath(XnatAbstractresource resource, String archivePath,
                                  String projectId, String sessionLabel, String resourceLabel,
                                  String scanId, String assessorId) {

        if (archivePath == null) return null;

        try {
            StringBuilder pathBuilder = new StringBuilder(archivePath);
            if (!archivePath.endsWith("/")) {
                pathBuilder.append("/");
            }

            pathBuilder.append(projectId).append("/");

            File projectDir = new File(pathBuilder.toString());
            if (projectDir.exists()) {
                File[] arcDirs = projectDir.listFiles(f -> f.isDirectory() && f.getName().startsWith("arc"));
                if (arcDirs != null && arcDirs.length > 0) {
                    pathBuilder = new StringBuilder(arcDirs[0].getAbsolutePath());
                    pathBuilder.append("/").append(sessionLabel);

                    if (scanId != null) {
                        pathBuilder.append("/SCANS/").append(scanId);
                    } else if (assessorId != null) {
                        pathBuilder.append("/ASSESSORS/").append(assessorId);
                    }

                    pathBuilder.append("/").append(resourceLabel).append("/catalog.xml");

                    File catalogFile = new File(pathBuilder.toString());
                    if (catalogFile.exists()) {
                        return catalogFile.getAbsolutePath();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not determine catalog path: {}", e.getMessage());
        }

        return null;
    }
}
