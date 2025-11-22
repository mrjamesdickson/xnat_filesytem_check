import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
import axios from 'axios';
import MockAdapter from 'axios-mock-adapter';
import CheckResultsViewer from './CheckResultsViewer';

const mockAxios = new MockAdapter(axios);

describe('CheckResultsViewer', () => {
  const testCheckId = 'check-123';

  beforeEach(() => {
    mockAxios.reset();
  });

  afterEach(() => {
    jest.clearAllMocks();
  });

  const mockSummary = {
    checkId: testCheckId,
    status: 'completed',
    startedAt: '2025-01-01T09:00:00Z',
    completedAt: '2025-01-01T10:00:00Z',
    totalFiles: 1000,
    processedFiles: 1000,
    filesFound: 950,
    filesMissing: 30,
    filesUnresolved: 20,
    statusCounts: {
      found: 950,
      missing: 30,
      unresolved: 20,
      error: 0
    }
  };

  const mockResults = [
    {
      project: 'PROJECT1',
      session: 'SESSION1',
      resource: 'DICOM',
      scope: 'scan',
      scanId: 'SCAN1',
      fileName: 'file1.dcm',
      filePath: '/archive/PROJECT1/arc001/SESSION1/SCANS/SCAN1/DICOM/file1.dcm',
      status: 'missing'
    },
    {
      project: 'PROJECT1',
      session: 'SESSION1',
      resource: 'NIFTI',
      scope: 'scan',
      scanId: 'SCAN2',
      fileName: 'file2.nii',
      filePath: null,
      status: 'unresolved'
    }
  ];

  test('renders loading state initially', () => {
    mockAxios.onGet(`/xapi/filesystem-check/checks/${testCheckId}/summary`).reply(200, mockSummary);

    render(<CheckResultsViewer checkId={testCheckId} />);

    expect(screen.getByText(/Loading check results/i)).toBeInTheDocument();
  });

  test('displays summary statistics', async () => {
    mockAxios.onGet(`/xapi/filesystem-check/checks/${testCheckId}/summary`).reply(200, mockSummary);

    render(<CheckResultsViewer checkId={testCheckId} />);

    await waitFor(() => {
      expect(screen.getByText(/Filesystem Check Results/i)).toBeInTheDocument();
      expect(screen.getByText(/1000/)).toBeInTheDocument(); // Total files
      expect(screen.getByText(/950/)).toBeInTheDocument(); // Found
      expect(screen.getByText(/30/)).toBeInTheDocument(); // Missing
      expect(screen.getByText(/20/)).toBeInTheDocument(); // Unresolved
    });
  });

  test('shows success message when no issues found', async () => {
    const perfectSummary = {
      ...mockSummary,
      filesMissing: 0,
      filesUnresolved: 0,
      statusCounts: { found: 1000, missing: 0, unresolved: 0, error: 0 }
    };

    mockAxios.onGet(`/xapi/filesystem-check/checks/${testCheckId}/summary`).reply(200, perfectSummary);

    render(<CheckResultsViewer checkId={testCheckId} />);

    await waitFor(() => {
      expect(screen.getByText(/All files verified successfully/i)).toBeInTheDocument();
    });
  });

  test('switches to missing files tab and loads results', async () => {
    mockAxios.onGet(`/xapi/filesystem-check/checks/${testCheckId}/summary`).reply(200, mockSummary);
    mockAxios.onGet(`/xapi/filesystem-check/checks/${testCheckId}/results`)
      .reply(200, {
        results: mockResults.filter(r => r.status === 'missing'),
        page: 0,
        size: 100,
        total: 1,
        totalPages: 1
      });

    render(<CheckResultsViewer checkId={testCheckId} />);

    await waitFor(() => {
      const missingTab = screen.getByText(/Missing Files/i);
      fireEvent.click(missingTab);
    });

    await waitFor(() => {
      expect(screen.getByText('file1.dcm')).toBeInTheDocument();
      expect(screen.getByText('PROJECT1')).toBeInTheDocument();
    });
  });

  test('displays pagination controls', async () => {
    mockAxios.onGet(`/xapi/filesystem-check/checks/${testCheckId}/summary`).reply(200, mockSummary);
    mockAxios.onGet(`/xapi/filesystem-check/checks/${testCheckId}/results`)
      .reply(200, {
        results: mockResults,
        page: 0,
        size: 100,
        total: 250,
        totalPages: 3
      });

    render(<CheckResultsViewer checkId={testCheckId} />);

    await waitFor(() => {
      const missingTab = screen.getByText(/Missing Files/i);
      fireEvent.click(missingTab);
    });

    await waitFor(() => {
      expect(screen.getByText(/Page 1 of 3/i)).toBeInTheDocument();
      expect(screen.getByText(/250 total/i)).toBeInTheDocument();
    });
  });

  test('navigates to next page', async () => {
    mockAxios.onGet(`/xapi/filesystem-check/checks/${testCheckId}/summary`).reply(200, mockSummary);
    mockAxios.onGet(`/xapi/filesystem-check/checks/${testCheckId}/results`)
      .reply((config) => {
        const params = new URLSearchParams(config.url.split('?')[1]);
        const page = parseInt(params.get('page') || '0');
        return [200, {
          results: mockResults,
          page: page,
          size: 100,
          total: 250,
          totalPages: 3
        }];
      });

    render(<CheckResultsViewer checkId={testCheckId} />);

    await waitFor(() => {
      const missingTab = screen.getByText(/Missing Files/i);
      fireEvent.click(missingTab);
    });

    await waitFor(() => {
      const nextButton = screen.getByText(/Next/i);
      fireEvent.click(nextButton);
    });

    await waitFor(() => {
      expect(screen.getByText(/Page 2 of 3/i)).toBeInTheDocument();
    });
  });

  test('triggers CSV export when export button clicked', async () => {
    mockAxios.onGet(`/xapi/filesystem-check/checks/${testCheckId}/summary`).reply(200, mockSummary);

    global.open = jest.fn();

    render(<CheckResultsViewer checkId={testCheckId} />);

    await waitFor(() => {
      const exportButton = screen.getByText(/Export All \(CSV\)/i);
      fireEvent.click(exportButton);
    });

    expect(global.open).toHaveBeenCalledWith(
      `/xapi/filesystem-check/checks/${testCheckId}/export/csv`,
      '_blank'
    );
  });

  test('triggers missing-only CSV export', async () => {
    mockAxios.onGet(`/xapi/filesystem-check/checks/${testCheckId}/summary`).reply(200, mockSummary);

    global.open = jest.fn();

    render(<CheckResultsViewer checkId={testCheckId} />);

    await waitFor(() => {
      const exportButton = screen.getByText(/Export Missing Only/i);
      fireEvent.click(exportButton);
    });

    expect(global.open).toHaveBeenCalledWith(
      `/xapi/filesystem-check/checks/${testCheckId}/export/csv?status=missing`,
      '_blank'
    );
  });

  test('displays error message when summary load fails', async () => {
    mockAxios.onGet(`/xapi/filesystem-check/checks/${testCheckId}/summary`).reply(500);

    render(<CheckResultsViewer checkId={testCheckId} />);

    await waitFor(() => {
      expect(screen.getByText(/Failed to load check summary/i)).toBeInTheDocument();
    });
  });
});
