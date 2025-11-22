package org.nrg.xnat.plugin.filesystemcheck.models;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FileCheckResult {
    private String project;
    private String session;
    private String resource;
    private String scope;
    private String scan;
    private String assessor;
    private String file;
    private String path;
    private String status;  // "found", "missing", "unresolved", "error"
    private String error;
}
