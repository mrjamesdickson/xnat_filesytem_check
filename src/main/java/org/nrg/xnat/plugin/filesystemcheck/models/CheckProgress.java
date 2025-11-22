package org.nrg.xnat.plugin.filesystemcheck.models;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CheckProgress {
    private String checkId;
    private String username;
    private String status;  // "running", "completed", "failed", "cancelled"

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant startedAt;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant completedAt;

    private Integer totalProjects;
    private Integer processedProjects;
    private Integer totalSessions;
    private Integer processedSessions;
    private Integer totalFiles;
    private Integer processedFiles;

    private String currentProject;
    private String currentSession;

    private Integer filesFound;
    private Integer filesMissing;
    private Integer filesUnresolved;

    private Double percentComplete;
    private String message;
}
