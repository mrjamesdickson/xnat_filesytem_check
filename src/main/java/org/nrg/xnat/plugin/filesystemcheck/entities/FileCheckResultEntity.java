/*
 * XNAT Filesystem Check Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package org.nrg.xnat.plugin.filesystemcheck.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;

import javax.persistence.*;
import java.time.Instant;

@Entity(name = "FsCheckFileResult")
@Table(name = "xhbm_fscheck_file_result", indexes = {
    @Index(name = "idx_fscheck_check_id", columnList = "checkId"),
    @Index(name = "idx_fscheck_status", columnList = "status"),
    @Index(name = "idx_fscheck_project", columnList = "project")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE, region = "nrg")
public class FileCheckResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String checkId;

    @Column(length = 255)
    private String project;

    @Column(length = 255)
    private String session;

    @Column(length = 255)
    private String resource;

    @Column(length = 64)
    private String scope;  // "session", "scan", "assessor"

    @Column(length = 255)
    private String scanId;

    @Column(length = 255)
    private String assessorId;

    @Column(length = 1000)
    private String fileName;

    @Column(length = 2000)
    private String filePath;

    @Column(length = 32)
    private String status;  // "found", "missing", "unresolved", "error"

    @Column(length = 2000)
    private String errorMessage;

    @Column
    private Long expectedSize;

    @Column
    private Long actualSize;

    @Column
    private Boolean sizeMatch;

    @Column
    private Instant checkedAt;

    @PrePersist
    protected void onCreate() {
        checkedAt = Instant.now();
    }
}
