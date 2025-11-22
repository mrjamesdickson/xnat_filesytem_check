package org.nrg.xnat.plugin.filesystemcheck.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "xhbm_fs_check")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE, region = "nrg")
public class FilesystemCheckEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 64)
    private String checkId;

    @Column(nullable = false, length = 255)
    private String username;

    @Column(length = 32)
    private String status;  // "queued", "running", "completed", "failed", "cancelled"

    @Column
    private Instant startedAt;

    @Column
    private Instant completedAt;

    @Column
    private Boolean entireArchive;

    @Column(length = 4000)
    private String projectIds;  // Comma-separated

    @Column
    private Integer maxFiles;

    @Column
    private Boolean verifyCatalogs;

    @Column
    private Integer totalProjects;

    @Column
    private Integer processedProjects;

    @Column
    private Integer totalSessions;

    @Column
    private Integer processedSessions;

    @Column
    private Long totalFiles;

    @Column
    private Long processedFiles;

    @Column
    private Long filesFound;

    @Column
    private Long filesMissing;

    @Column
    private Long filesUnresolved;

    @Column(length = 1000)
    private String currentProject;

    @Column(length = 1000)
    private String currentSession;

    @Column
    private Double percentComplete;

    @Column(length = 4000)
    private String errorMessage;

    @Column
    private Instant createdAt;

    @Column
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
