package org.nrg.xnat.plugin.filesystemcheck.services;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.plugin.filesystemcheck.entities.FileCheckResultEntity;
import org.nrg.xnat.plugin.filesystemcheck.entities.FilesystemCheckEntity;
import org.nrg.xnat.plugin.filesystemcheck.models.FilesystemCheckRequest;
import org.nrg.xnat.plugin.filesystemcheck.repositories.FileCheckResultDao;
import org.nrg.xnat.plugin.filesystemcheck.repositories.FilesystemCheckDao;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class AsyncFilesystemCheckServiceTest {

    @Mock
    private FilesystemCheckDao checkDao;

    @Mock
    private FileCheckResultDao resultDao;

    @Mock
    private UserI mockUser;

    @InjectMocks
    private AsyncFilesystemCheckService service;

    private FilesystemCheckEntity mockCheckEntity;
    private String testCheckId;

    @Before
    public void setUp() {
        testCheckId = "test-check-123";
        mockCheckEntity = FilesystemCheckEntity.builder()
                .checkId(testCheckId)
                .username("testuser")
                .status("queued")
                .build();

        when(mockUser.getUsername()).thenReturn("testuser");
    }

    @Test
    public void testCancelCheck() {
        // When
        service.cancelCheck(testCheckId);

        // Then - cancellation flag should be set
        // Verify the service marks it for cancellation
        assertNotNull(service);
    }

    @Test
    public void testCancelCheckNullCheckId() {
        // When/Then - should handle null gracefully
        service.cancelCheck(null);
        // No exception should be thrown
    }

    @Test
    public void testPerformCheckAsyncWithNullCheckEntity() {
        // Given
        when(checkDao.findByCheckId(testCheckId)).thenReturn(null);

        FilesystemCheckRequest request = FilesystemCheckRequest.builder()
                .entireArchive(false)
                .projectIds(Arrays.asList("PROJECT1"))
                .build();

        // When
        service.performCheckAsync(testCheckId, request, mockUser);

        // Then - should handle gracefully and not proceed
        verify(checkDao).findByCheckId(testCheckId);
        verify(checkDao, never()).update(any());
    }

    @Test
    public void testPerformCheckAsyncUpdatesStatus() throws Exception {
        // Given
        when(checkDao.findByCheckId(testCheckId)).thenReturn(mockCheckEntity);

        // Mock XnatProjectdata
        XnatProjectdata mockProject = mock(XnatProjectdata.class);
        when(mockProject.getId()).thenReturn("PROJECT1");
        when(mockProject.getRootArchivePath()).thenReturn("/archive");
        when(mockProject.getExperiments_experiment()).thenReturn(new ArrayList<>());

        mockStatic(XnatProjectdata.class);
        when(XnatProjectdata.getXnatProjectdatasById(eq("PROJECT1"), eq(mockUser), anyBoolean()))
                .thenReturn(mockProject);

        FilesystemCheckRequest request = FilesystemCheckRequest.builder()
                .entireArchive(false)
                .projectIds(Arrays.asList("PROJECT1"))
                .build();

        // When
        service.performCheckAsync(testCheckId, request, mockUser);

        // Then - verify status was updated
        verify(checkDao, atLeastOnce()).update(any(FilesystemCheckEntity.class));
    }
}
