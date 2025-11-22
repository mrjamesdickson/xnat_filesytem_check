package org.nrg.xnat.plugin.filesystemcheck.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.nrg.xapi.exceptions.NotFoundException;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xdat.security.helpers.Permissions;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.plugin.filesystemcheck.entities.FileCheckResultEntity;
import org.nrg.xnat.plugin.filesystemcheck.entities.FilesystemCheckEntity;
import org.nrg.xnat.plugin.filesystemcheck.models.FilesystemCheckRequest;
import org.nrg.xnat.plugin.filesystemcheck.repositories.FileCheckResultDao;
import org.nrg.xnat.plugin.filesystemcheck.repositories.FilesystemCheckDao;
import org.nrg.xnat.plugin.filesystemcheck.services.AsyncFilesystemCheckService;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class FilesystemCheckRestApiTest {

    @Mock
    private AsyncFilesystemCheckService asyncFilesystemCheckService;

    @Mock
    private FilesystemCheckDao checkDao;

    @Mock
    private FileCheckResultDao resultDao;

    @Mock
    private UserManagementServiceI userManagementService;

    @Mock
    private RoleHolder roleHolder;

    @Mock
    private UserI mockUser;

    @InjectMocks
    private FilesystemCheckRestApi restApi;

    private FilesystemCheckEntity mockCheck;
    private String testCheckId;

    @Before
    public void setUp() {
        testCheckId = "test-check-123";
        when(mockUser.getUsername()).thenReturn("testuser");

        mockCheck = FilesystemCheckEntity.builder()
                .checkId(testCheckId)
                .username("testuser")
                .status("completed")
                .startedAt(Instant.now().minusSeconds(3600))
                .completedAt(Instant.now())
                .totalFiles(1000L)
                .processedFiles(1000L)
                .filesFound(950L)
                .filesMissing(30L)
                .filesUnresolved(20L)
                .percentComplete(100.0)
                .build();
    }

    @Test
    public void testStartCheckReturnsCheckId() {
        // Given
        FilesystemCheckRequest request = FilesystemCheckRequest.builder()
                .entireArchive(false)
                .projectIds(Arrays.asList("PROJECT1"))
                .maxFiles(1000)
                .build();

        when(checkDao.create(any(FilesystemCheckEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        ResponseEntity<Map<String, String>> response = restApi.startCheck(request);

        // Then
        assertNotNull(response);
        assertEquals(200, response.getStatusCodeValue());
        Map<String, String> body = response.getBody();
        assertNotNull(body);
        assertTrue(body.containsKey("checkId"));
        assertTrue(body.containsKey("status"));
        assertEquals("queued", body.get("status"));

        verify(checkDao).create(any(FilesystemCheckEntity.class));
        verify(asyncFilesystemCheckService).performCheckAsync(anyString(), eq(request), any());
    }

    @Test
    public void testGetCheckStatusReturnsCheck() {
        // Given
        when(checkDao.findByCheckId(testCheckId)).thenReturn(mockCheck);

        // When
        ResponseEntity<FilesystemCheckEntity> response = restApi.getCheckStatus(testCheckId);

        // Then
        assertNotNull(response);
        assertEquals(200, response.getStatusCodeValue());
        assertEquals(mockCheck, response.getBody());

        verify(checkDao).findByCheckId(testCheckId);
    }

    @Test(expected = NotFoundException.class)
    public void testGetCheckStatusThrowsNotFoundForInvalidId() {
        // Given
        when(checkDao.findByCheckId("invalid-id")).thenReturn(null);

        // When
        restApi.getCheckStatus("invalid-id");

        // Then - exception thrown
    }

    @Test
    public void testGetCheckResultsReturnsPaginatedResults() {
        // Given
        List<FileCheckResultEntity> results = Arrays.asList(
                FileCheckResultEntity.builder()
                        .checkId(testCheckId)
                        .project("PROJECT1")
                        .session("SESSION1")
                        .fileName("file1.dcm")
                        .status("found")
                        .build(),
                FileCheckResultEntity.builder()
                        .checkId(testCheckId)
                        .project("PROJECT1")
                        .session("SESSION1")
                        .fileName("file2.dcm")
                        .status("missing")
                        .build()
        );

        when(checkDao.findByCheckId(testCheckId)).thenReturn(mockCheck);
        when(resultDao.findByCheckId(eq(testCheckId), eq(0), eq(100))).thenReturn(results);
        when(resultDao.countByCheckId(testCheckId)).thenReturn(2L);

        // When
        ResponseEntity<Map<String, Object>> response = restApi.getCheckResults(testCheckId, 0, 100, null);

        // Then
        assertNotNull(response);
        assertEquals(200, response.getStatusCodeValue());

        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(results, body.get("results"));
        assertEquals(0, body.get("page"));
        assertEquals(100, body.get("size"));
        assertEquals(2L, body.get("total"));

        verify(resultDao).findByCheckId(testCheckId, 0, 100);
        verify(resultDao).countByCheckId(testCheckId);
    }

    @Test
    public void testGetCheckSummaryReturnsStatistics() {
        // Given
        when(checkDao.findByCheckId(testCheckId)).thenReturn(mockCheck);
        when(resultDao.countByCheckIdAndStatus(testCheckId, "found")).thenReturn(950L);
        when(resultDao.countByCheckIdAndStatus(testCheckId, "missing")).thenReturn(30L);
        when(resultDao.countByCheckIdAndStatus(testCheckId, "unresolved")).thenReturn(20L);
        when(resultDao.countByCheckIdAndStatus(testCheckId, "error")).thenReturn(0L);

        // When
        ResponseEntity<Map<String, Object>> response = restApi.getCheckSummary(testCheckId);

        // Then
        assertNotNull(response);
        assertEquals(200, response.getStatusCodeValue());

        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(testCheckId, body.get("checkId"));
        assertEquals("completed", body.get("status"));
        assertEquals(1000L, body.get("totalFiles"));
        assertEquals(950L, body.get("filesFound"));
        assertEquals(30L, body.get("filesMissing"));
        assertEquals(20L, body.get("filesUnresolved"));

        Map<String, Long> statusCounts = (Map<String, Long>) body.get("statusCounts");
        assertNotNull(statusCounts);
        assertEquals(Long.valueOf(950), statusCounts.get("found"));
        assertEquals(Long.valueOf(30), statusCounts.get("missing"));
        assertEquals(Long.valueOf(20), statusCounts.get("unresolved"));

        verify(checkDao).findByCheckId(testCheckId);
    }

    @Test
    public void testCancelCheckUpdatesStatus() {
        // Given
        FilesystemCheckEntity runningCheck = FilesystemCheckEntity.builder()
                .checkId(testCheckId)
                .username("testuser")
                .status("running")
                .startedAt(Instant.now())
                .build();

        when(checkDao.findByCheckId(testCheckId)).thenReturn(runningCheck);

        // When
        ResponseEntity<Map<String, String>> response = restApi.cancelCheck(testCheckId);

        // Then
        assertNotNull(response);
        assertEquals(200, response.getStatusCodeValue());

        Map<String, String> body = response.getBody();
        assertNotNull(body);
        assertEquals("cancelled", body.get("status"));

        verify(asyncFilesystemCheckService).cancelCheck(testCheckId);
        verify(checkDao).update(argThat(check ->
                "cancelled".equals(check.getStatus()) && check.getCompletedAt() != null
        ));
    }

    @Test
    public void testGetStatusReturnsServiceInfo() {
        // Given
        when(checkDao.findActiveChecks()).thenReturn(Arrays.asList(mockCheck));

        // When
        ResponseEntity<Map<String, Object>> response = restApi.getStatus();

        // Then
        assertNotNull(response);
        assertEquals(200, response.getStatusCodeValue());

        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("filesystem-check", body.get("service"));
        assertEquals("available", body.get("status"));
        assertEquals(1, body.get("activeChecks"));

        verify(checkDao).findActiveChecks();
    }
}
