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
import org.nrg.xft.event.EventUtils;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.plugin.filesystemcheck.entities.FileCheckResultEntity;
import org.nrg.xnat.plugin.filesystemcheck.entities.FilesystemCheckEntity;
import org.nrg.xnat.plugin.filesystemcheck.models.CheckProgress;
import org.nrg.xnat.plugin.filesystemcheck.models.FilesystemCheckRequest;
import org.nrg.xnat.plugin.filesystemcheck.repositories.FileCheckResultDao;
import org.nrg.xnat.plugin.filesystemcheck.repositories.FilesystemCheckDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
@Service("fsCheckAsyncService")
public class AsyncFilesystemCheckService {

    @Autowired
    private FilesystemCheckDao checkDao;

    @Autowired
    private FileCheckResultDao resultDao;

    private final ExecutorService fileCheckExecutor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors()
    );

    private final ConcurrentHashMap<String, Boolean> cancellationFlags = new ConcurrentHashMap<>();

    /**
     * Start an asynchronous filesystem check
     */
    @Async
    @Transactional
    public void performCheckAsync(String checkId, FilesystemCheckRequest request, UserI user) {
        log.info("Starting async filesystem check {}", checkId);

        FilesystemCheckEntity checkEntity = checkDao.findByCheckId(checkId);
        if (checkEntity == null) {
            log.error("Check entity not found: {}", checkId);
            return;
        }

        try {
            // Update status to running
            checkEntity.setStatus("running");
            checkEntity.setStartedAt(Instant.now());
            checkDao.update(checkEntity);

            // Perform the check
            performCheckWithStreaming(checkId, checkEntity, request, user);

            // Mark as completed
            checkEntity.setStatus("completed");
            checkEntity.setCompletedAt(Instant.now());
            checkEntity.setPercentComplete(100.0);
            checkDao.update(checkEntity);

            log.info("Filesystem check {} completed successfully", checkId);

        } catch (Exception e) {
            log.error("Filesystem check {} failed: {}", checkId, e.getMessage(), e);
            checkEntity.setStatus("failed");
            checkEntity.setCompletedAt(Instant.now());
            checkEntity.setErrorMessage(e.getMessage());
            checkDao.update(checkEntity);
        } finally {
            cancellationFlags.remove(checkId);
        }
    }

    private void performCheckWithStreaming(String checkId, FilesystemCheckEntity checkEntity,
                                          FilesystemCheckRequest request, UserI user) throws Exception {

        List<String> projectsToCheck = determineProjects(request, user);

        checkEntity.setTotalProjects(projectsToCheck.size());
        checkDao.update(checkEntity);

        AtomicLong totalFiles = new AtomicLong(0);
        AtomicLong processedFiles = new AtomicLong(0);
        AtomicLong filesFound = new AtomicLong(0);
        AtomicLong filesMissing = new AtomicLong(0);
        AtomicLong filesUnresolved = new AtomicLong(0);

        int processedProjects = 0;

        for (String projectId : projectsToCheck) {
            if (isCancelled(checkId)) {
                log.info("Check {} cancelled by user", checkId);
                checkEntity.setStatus("cancelled");
                return;
            }

            try {
                XnatProjectdata project = XnatProjectdata.getXnatProjectdatasById(projectId, user, false);
                if (project == null) {
                    log.warn("Project not found or no access: {}", projectId);
                    continue;
                }

                checkEntity.setCurrentProject(projectId);
                checkDao.update(checkEntity);

                // Process project with parallel file checking
                processProjectParallel(checkId, project, user, request.getMaxFiles(),
                        totalFiles, processedFiles, filesFound, filesMissing, filesUnresolved,
                        checkEntity);

                processedProjects++;
                checkEntity.setProcessedProjects(processedProjects);

                // Update progress
                double progress = (processedProjects * 100.0) / projectsToCheck.size();
                checkEntity.setPercentComplete(progress);
                checkDao.update(checkEntity);

            } catch (Exception e) {
                log.error("Error checking project {}: {}", projectId, e.getMessage(), e);
            }
        }

        // Final stats update
        checkEntity.setTotalFiles(totalFiles.get());
        checkEntity.setProcessedFiles(processedFiles.get());
        checkEntity.setFilesFound(filesFound.get());
        checkEntity.setFilesMissing(filesMissing.get());
        checkEntity.setFilesUnresolved(filesUnresolved.get());
        checkDao.update(checkEntity);
    }

    private void processProjectParallel(String checkId, XnatProjectdata project, UserI user,
                                       Integer maxFiles, AtomicLong totalFiles,
                                       AtomicLong processedFiles, AtomicLong filesFound,
                                       AtomicLong filesMissing, AtomicLong filesUnresolved,
                                       FilesystemCheckEntity checkEntity) {

        String projectId = project.getId();
        String archivePath = project.getRootArchivePath();

        List<? extends XnatExperimentdataI> sessionsI = project.getExperiments();

        checkEntity.setTotalSessions(sessionsI.size());
        checkDao.update(checkEntity);

        int processedSessions = 0;

        for (XnatExperimentdataI sessionI : sessionsI) {
            if (isCancelled(checkId) || (maxFiles != null && totalFiles.get() >= maxFiles)) {
                break;
            }

            if (!(sessionI instanceof XnatExperimentdata)) {
                continue;
            }
            XnatExperimentdata session = (XnatExperimentdata) sessionI;

            checkEntity.setCurrentSession(session.getLabel());
            checkDao.update(checkEntity);

            processSession(checkId, projectId, session, archivePath, user, maxFiles,
                    totalFiles, processedFiles, filesFound, filesMissing, filesUnresolved);

            processedSessions++;
            checkEntity.setProcessedSessions(processedSessions);
            checkDao.update(checkEntity);
        }
    }

    private void processSession(String checkId, String projectId, XnatExperimentdata session,
                               String archivePath, UserI user, Integer maxFiles,
                               AtomicLong totalFiles, AtomicLong processedFiles,
                               AtomicLong filesFound, AtomicLong filesMissing,
                               AtomicLong filesUnresolved) {

        String sessionLabel = session.getLabel();

        if (!(session instanceof XnatImagesessiondata)) {
            return;
        }

        XnatImagesessiondata imageSession = (XnatImagesessiondata) session;
        List<Future<?>> futures = new ArrayList<>();

        // Process session resources
        for (XnatAbstractresourceI resourceI : imageSession.getResources_resource()) {
            if (isCancelled(checkId) || (maxFiles != null && totalFiles.get() >= maxFiles)) {
                break;
            }
            if (resourceI instanceof XnatAbstractresource) {
                XnatAbstractresource resource = (XnatAbstractresource) resourceI;
                futures.add(fileCheckExecutor.submit(() ->
                        processResource(checkId, projectId, sessionLabel, resource, "session",
                                null, null, archivePath, totalFiles, processedFiles,
                                filesFound, filesMissing, filesUnresolved, maxFiles)
                ));
            }
        }

        // Process scan resources
        for (XnatImagescandataI scanI : imageSession.getScans_scan()) {
            if (isCancelled(checkId) || (maxFiles != null && totalFiles.get() >= maxFiles)) {
                break;
            }
            String scanId = scanI.getId();
            for (XnatAbstractresourceI resourceI : scanI.getFile()) {
                if (resourceI instanceof XnatAbstractresource) {
                    XnatAbstractresource resource = (XnatAbstractresource) resourceI;
                    futures.add(fileCheckExecutor.submit(() ->
                            processResource(checkId, projectId, sessionLabel, resource, "scan",
                                    scanId, null, archivePath, totalFiles, processedFiles,
                                    filesFound, filesMissing, filesUnresolved, maxFiles)
                    ));
                }
            }
        }

        // Process assessor resources
        for (XnatImageassessordataI assessorI : imageSession.getAssessors_assessor()) {
            if (isCancelled(checkId) || (maxFiles != null && totalFiles.get() >= maxFiles)) {
                break;
            }
            String assessorId = assessorI.getId();
            for (XnatAbstractresourceI resourceI : assessorI.getOut_file()) {
                if (resourceI instanceof XnatAbstractresource) {
                    XnatAbstractresource resource = (XnatAbstractresource) resourceI;
                    futures.add(fileCheckExecutor.submit(() ->
                            processResource(checkId, projectId, sessionLabel, resource, "assessor",
                                    null, assessorId, archivePath, totalFiles, processedFiles,
                                    filesFound, filesMissing, filesUnresolved, maxFiles)
                    ));
                }
            }
        }

        // Wait for all resource checks to complete
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                log.error("Error processing resource: {}", e.getMessage());
            }
        }
    }

    @Transactional
    private void processResource(String checkId, String projectId, String sessionLabel,
                                XnatAbstractresource resource, String scope,
                                String scanId, String assessorId, String archivePath,
                                AtomicLong totalFiles, AtomicLong processedFiles,
                                AtomicLong filesFound, AtomicLong filesMissing,
                                AtomicLong filesUnresolved, Integer maxFiles) {

        if (!(resource instanceof XnatResourcecatalog)) {
            return;
        }

        XnatResourcecatalog catalogResource = (XnatResourcecatalog) resource;
        String resourceLabel = resource.getLabel();

        List<FileCheckResultEntity> batch = new ArrayList<>();

        try {
            // Build resource path for catalog lookup
            String resourcePath = buildResourcePath(archivePath, projectId, sessionLabel, scanId, assessorId, resourceLabel);
            CatCatalogBean catalog = catalogResource.getCatalog(resourcePath);

            if (catalog == null || catalog.getEntries_entry() == null) {
                log.debug("No catalog entries found for resource {} in {}/{}", resourceLabel, projectId, sessionLabel);
                return;
            }

            for (Object entryObj : catalog.getEntries_entry()) {
                if (isCancelled(checkId) || (maxFiles != null && totalFiles.get() >= maxFiles)) {
                    break;
                }

                if (entryObj instanceof CatEntryBean) {
                    CatEntryBean entry = (CatEntryBean) entryObj;
                    totalFiles.incrementAndGet();

                    FileCheckResultEntity result = checkFileAndCreateEntity(checkId, projectId,
                            sessionLabel, resourceLabel, scope, scanId, assessorId,
                            entry, resourcePath);

                    if (result != null) {
                        batch.add(result);
                        processedFiles.incrementAndGet();

                        switch (result.getStatus()) {
                            case "found":
                                filesFound.incrementAndGet();
                                break;
                            case "missing":
                                filesMissing.incrementAndGet();
                                break;
                            case "unresolved":
                                filesUnresolved.incrementAndGet();
                                break;
                        }

                        // Batch save every 50 records
                        if (batch.size() >= 50) {
                            resultDao.saveBatch(batch);
                            batch.clear();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error processing resource {} in {}/{}: {}", resourceLabel, projectId, sessionLabel, e.getMessage());
        }

        // Save remaining batch
        if (!batch.isEmpty()) {
            resultDao.saveBatch(batch);
        }
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

    private FileCheckResultEntity checkFileAndCreateEntity(String checkId, String projectId,
                                                          String sessionLabel, String resourceLabel,
                                                          String scope, String scanId,
                                                          String assessorId,
                                                          CatEntryBean entry,
                                                          String resourcePath) {

        String fileName = entry.getName();
        String uri = entry.getUri();

        try {
            Path filePath = resolveFilePath(uri, resourcePath, projectId, sessionLabel,
                    resourceLabel, scanId, assessorId);

            FileCheckResultEntity.FileCheckResultEntityBuilder builder = FileCheckResultEntity.builder()
                    .checkId(checkId)
                    .project(projectId)
                    .session(sessionLabel)
                    .resource(resourceLabel)
                    .scope(scope)
                    .scanId(scanId)
                    .assessorId(assessorId)
                    .fileName(fileName);

            if (filePath == null) {
                return builder.status("unresolved").build();
            }

            builder.filePath(filePath.toString());

            if (Files.exists(filePath)) {
                try {
                    long size = Files.size(filePath);
                    builder.actualSize(size);
                } catch (Exception e) {
                    log.debug("Could not get file size: {}", e.getMessage());
                }
                return builder.status("found").build();
            } else {
                return builder.status("missing").build();
            }

        } catch (Exception e) {
            log.error("Error checking file {}: {}", fileName, e.getMessage());
            return FileCheckResultEntity.builder()
                    .checkId(checkId)
                    .project(projectId)
                    .session(sessionLabel)
                    .resource(resourceLabel)
                    .scope(scope)
                    .fileName(fileName)
                    .status("error")
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    private Path resolveFilePath(String uri, String resourcePath,
                                String projectId, String sessionLabel, String resourceLabel,
                                String scanId, String assessorId) {

        // Strategy 1: Absolute path
        if (uri != null && uri.startsWith("/")) {
            Path absPath = Paths.get(uri);
            if (Files.exists(absPath)) {
                return absPath;
            }
        }

        // Strategy 2: Relative to resource path
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

    private List<String> determineProjects(FilesystemCheckRequest request, UserI user) throws Exception {
        if (request.getEntireArchive() != null && request.getEntireArchive()) {
            List<String> allProjects = new ArrayList<>();
            ArrayList<XnatProjectdata> projects = XnatProjectdata.getAllXnatProjectdatas(user, false);
            for (XnatProjectdata project : projects) {
                allProjects.add(project.getId());
            }
            return allProjects;
        } else if (request.getProjectIds() != null && !request.getProjectIds().isEmpty()) {
            return request.getProjectIds();
        }
        return Collections.emptyList();
    }

    public void cancelCheck(String checkId) {
        cancellationFlags.put(checkId, true);
        log.info("Cancellation requested for check {}", checkId);
    }

    private boolean isCancelled(String checkId) {
        return cancellationFlags.getOrDefault(checkId, false);
    }
}
