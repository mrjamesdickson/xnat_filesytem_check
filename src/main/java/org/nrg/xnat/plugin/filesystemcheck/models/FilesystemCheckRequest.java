package org.nrg.xnat.plugin.filesystemcheck.models;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FilesystemCheckRequest {
    private List<String> projectIds;
    private Boolean entireArchive;
    private Integer maxFiles;
    private Boolean verifyCatalogs;
}
