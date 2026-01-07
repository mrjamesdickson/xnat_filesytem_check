/*
 * XNAT Filesystem Check Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package org.nrg.xnat.plugin.filesystemcheck.models;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FileCheckResultWithSize extends FileCheckResult {
    private Long expectedSize;   // Size from XNAT database
    private Long actualSize;     // Size on filesystem
    private Boolean sizeMatch;   // Do sizes match?

    @Builder(builderMethodName = "extendedBuilder")
    public FileCheckResultWithSize(String project, String session, String resource,
                                    String scope, String scan, String assessor,
                                    String file, String path, String status, String error,
                                    Long expectedSize, Long actualSize, Boolean sizeMatch) {
        super(project, session, resource, scope, scan, assessor, file, path, status, error);
        this.expectedSize = expectedSize;
        this.actualSize = actualSize;
        this.sizeMatch = sizeMatch;
    }
}
