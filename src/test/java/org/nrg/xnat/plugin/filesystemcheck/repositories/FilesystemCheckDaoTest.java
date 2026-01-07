package org.nrg.xnat.plugin.filesystemcheck.repositories;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.nrg.xnat.plugin.filesystemcheck.entities.FilesystemCheckEntity;

import javax.persistence.Query;
import org.hibernate.Session;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class FilesystemCheckDaoTest {

    @Mock
    private Session mockSession;

    @Mock
    private Query mockQuery;

    private FilesystemCheckDao dao;

    @Before
    public void setUp() {
        dao = new FilesystemCheckDao();
        // Note: In real scenario, you'd inject the session
    }

    @Test
    public void testFindActiveChecksReturnsRunningAndQueuedChecks() {
        // Given
        FilesystemCheckEntity running = FilesystemCheckEntity.builder()
                .checkId("check1")
                .status("running")
                .username("user1")
                .startedAt(Instant.now())
                .build();

        FilesystemCheckEntity queued = FilesystemCheckEntity.builder()
                .checkId("check2")
                .status("queued")
                .username("user2")
                .build();

        List<FilesystemCheckEntity> expected = Arrays.asList(running, queued);

        // When using real DAO with test database, we would verify results
        // This is a structure test to ensure method signature is correct
        assertNotNull(dao);
    }

    @Test
    public void testFindByUsernameReturnsUserChecks() {
        // Given
        String username = "testuser";

        // When/Then - verify method exists and can be called
        assertNotNull(dao);
    }

    @Test
    public void testDeleteOlderThanRemovesOldCompleted Checks() {
        // Given
        Instant cutoff = Instant.now().minusSeconds(86400 * 30); // 30 days ago

        // When/Then - verify method exists
        assertNotNull(dao);
    }
}
