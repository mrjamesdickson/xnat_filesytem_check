/*
 * XNAT Filesystem Check Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package org.nrg.xnat.plugin.filesystemcheck.services;

import lombok.extern.slf4j.Slf4j;
import org.nrg.xnat.plugin.filesystemcheck.models.CheckProgress;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service("fsCheckProgressService")
public class ProgressTrackingService {

    private final Map<String, CheckProgress> activeChecks = new ConcurrentHashMap<>();
    private final Map<String, CheckProgress> completedChecks = new ConcurrentHashMap<>();
    private static final int MAX_COMPLETED_HISTORY = 100;

    /**
     * Start tracking a new check
     */
    public String startCheck(String username, int totalProjects) {
        String checkId = UUID.randomUUID().toString();

        CheckProgress progress = CheckProgress.builder()
                .checkId(checkId)
                .username(username)
                .status("running")
                .startedAt(Instant.now())
                .totalProjects(totalProjects)
                .processedProjects(0)
                .totalSessions(0)
                .processedSessions(0)
                .totalFiles(0)
                .processedFiles(0)
                .filesFound(0)
                .filesMissing(0)
                .filesUnresolved(0)
                .percentComplete(0.0)
                .build();

        activeChecks.put(checkId, progress);
        log.info("Started tracking check {} for user {}", checkId, username);

        return checkId;
    }

    /**
     * Update progress for a running check
     */
    public void updateProgress(String checkId, CheckProgress updates) {
        CheckProgress progress = activeChecks.get(checkId);
        if (progress == null) {
            log.warn("Attempted to update non-existent check: {}", checkId);
            return;
        }

        if (updates.getProcessedProjects() != null) {
            progress.setProcessedProjects(updates.getProcessedProjects());
        }
        if (updates.getTotalSessions() != null) {
            progress.setTotalSessions(updates.getTotalSessions());
        }
        if (updates.getProcessedSessions() != null) {
            progress.setProcessedSessions(updates.getProcessedSessions());
        }
        if (updates.getTotalFiles() != null) {
            progress.setTotalFiles(updates.getTotalFiles());
        }
        if (updates.getProcessedFiles() != null) {
            progress.setProcessedFiles(updates.getProcessedFiles());
        }
        if (updates.getCurrentProject() != null) {
            progress.setCurrentProject(updates.getCurrentProject());
        }
        if (updates.getCurrentSession() != null) {
            progress.setCurrentSession(updates.getCurrentSession());
        }
        if (updates.getFilesFound() != null) {
            progress.setFilesFound(updates.getFilesFound());
        }
        if (updates.getFilesMissing() != null) {
            progress.setFilesMissing(updates.getFilesMissing());
        }
        if (updates.getFilesUnresolved() != null) {
            progress.setFilesUnresolved(updates.getFilesUnresolved());
        }

        // Calculate percent complete
        if (progress.getTotalProjects() != null && progress.getTotalProjects() > 0) {
            double projectPercent = (progress.getProcessedProjects() * 100.0) / progress.getTotalProjects();
            progress.setPercentComplete(Math.min(99.0, projectPercent));  // Cap at 99% until complete
        }
    }

    /**
     * Mark a check as completed
     */
    public void completeCheck(String checkId, String status, String message) {
        CheckProgress progress = activeChecks.remove(checkId);
        if (progress == null) {
            log.warn("Attempted to complete non-existent check: {}", checkId);
            return;
        }

        progress.setStatus(status);
        progress.setCompletedAt(Instant.now());
        progress.setPercentComplete(100.0);
        progress.setMessage(message);

        // Move to completed history
        completedChecks.put(checkId, progress);

        // Trim history if too large
        if (completedChecks.size() > MAX_COMPLETED_HISTORY) {
            // Remove oldest entry
            completedChecks.entrySet().stream()
                    .min((a, b) -> a.getValue().getCompletedAt().compareTo(b.getValue().getCompletedAt()))
                    .ifPresent(entry -> completedChecks.remove(entry.getKey()));
        }

        log.info("Completed check {} with status: {}", checkId, status);
    }

    /**
     * Get progress for a specific check
     */
    public CheckProgress getProgress(String checkId) {
        CheckProgress progress = activeChecks.get(checkId);
        if (progress != null) {
            return progress;
        }
        return completedChecks.get(checkId);
    }

    /**
     * Get all active checks
     */
    public Map<String, CheckProgress> getActiveChecks() {
        return new ConcurrentHashMap<>(activeChecks);
    }

    /**
     * Get all completed checks
     */
    public Map<String, CheckProgress> getCompletedChecks() {
        return new ConcurrentHashMap<>(completedChecks);
    }

    /**
     * Get all checks for a specific user
     */
    public Map<String, CheckProgress> getChecksForUser(String username) {
        Map<String, CheckProgress> userChecks = new ConcurrentHashMap<>();

        activeChecks.forEach((id, progress) -> {
            if (username.equals(progress.getUsername())) {
                userChecks.put(id, progress);
            }
        });

        completedChecks.forEach((id, progress) -> {
            if (username.equals(progress.getUsername())) {
                userChecks.put(id, progress);
            }
        });

        return userChecks;
    }

    /**
     * Cancel a running check
     */
    public void cancelCheck(String checkId) {
        completeCheck(checkId, "cancelled", "Check was cancelled by user");
    }
}
