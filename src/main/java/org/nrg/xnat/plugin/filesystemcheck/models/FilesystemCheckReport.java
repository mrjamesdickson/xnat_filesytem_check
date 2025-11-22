package org.nrg.xnat.plugin.filesystemcheck.models;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FilesystemCheckReport {
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant generatedAt;

    private Map<String, Integer> stats;
    private List<FileCheckResult> missingFiles;
    private List<FileCheckResult> unresolvedFiles;
    private List<ResourceCheckResult> resourceDetails;

    private String status;  // "completed", "running", "failed"
    private String message;
}
