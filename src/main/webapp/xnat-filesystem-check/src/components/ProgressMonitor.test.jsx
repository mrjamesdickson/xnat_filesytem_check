import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
import axios from 'axios';
import MockAdapter from 'axios-mock-adapter';
import ProgressMonitor from './ProgressMonitor';

const mockAxios = new MockAdapter(axios);

describe('ProgressMonitor', () => {
  const mockOnViewResults = jest.fn();

  beforeEach(() => {
    mockAxios.reset();
    jest.clearAllTimers();
    jest.useFakeTimers();
  });

  afterEach(() => {
    jest.clearAllMocks();
    jest.useRealTimers();
  });

  const mockRunningCheck = {
    checkId: 'check-123',
    username: 'testuser',
    status: 'running',
    startedAt: '2025-01-01T10:00:00Z',
    percentComplete: 45.5,
    currentProject: 'PROJECT1',
    currentSession: 'SESSION1',
    processedProjects: 5,
    totalProjects: 10,
    processedFiles: 1000,
    totalFiles: 2200,
    filesFound: 900,
    filesMissing: 50,
    filesUnresolved: 50
  };

  const mockCompletedCheck = {
    checkId: 'check-456',
    username: 'testuser',
    status: 'completed',
    startedAt: '2025-01-01T09:00:00Z',
    completedAt: '2025-01-01T10:00:00Z',
    percentComplete: 100,
    processedFiles: 5000,
    filesFound: 4950,
    filesMissing: 30,
    filesUnresolved: 20
  };

  test('renders with no checks message when empty', async () => {
    mockAxios.onGet('/xapi/filesystem-check/checks/mine').reply(200, []);
    mockAxios.onGet('/xapi/users/me').reply(200, { admin: false });

    render(<ProgressMonitor />);

    await waitFor(() => {
      expect(screen.getByText(/No checks in progress or history/i)).toBeInTheDocument();
    });
  });

  test('displays running check with progress bar', async () => {
    mockAxios.onGet('/xapi/filesystem-check/checks/mine').reply(200, [mockRunningCheck]);
    mockAxios.onGet('/xapi/users/me').reply(200, { admin: false });

    render(<ProgressMonitor />);

    await waitFor(() => {
      expect(screen.getByText(/Check ID: check-123/i)).toBeInTheDocument();
      expect(screen.getByText('running')).toBeInTheDocument();
      expect(screen.getByText(/45%/i)).toBeInTheDocument();
      expect(screen.getByText(/PROJECT1/i)).toBeInTheDocument();
      expect(screen.getByText(/SESSION1/i)).toBeInTheDocument();
    });
  });

  test('displays completed check with statistics', async () => {
    mockAxios.onGet('/xapi/filesystem-check/checks/mine').reply(200, [mockCompletedCheck]);
    mockAxios.onGet('/xapi/users/me').reply(200, { admin: false });

    render(<ProgressMonitor onViewResults={mockOnViewResults} />);

    await waitFor(() => {
      expect(screen.getByText(/Check ID: check-456/i)).toBeInTheDocument();
      expect(screen.getByText('completed')).toBeInTheDocument();
      expect(screen.getByText(/Total Files Checked/i)).toBeInTheDocument();
      expect(screen.getByText(/5000/)).toBeInTheDocument();
    });
  });

  test('calls onViewResults when View Results button clicked', async () => {
    mockAxios.onGet('/xapi/filesystem-check/checks/mine').reply(200, [mockCompletedCheck]);
    mockAxios.onGet('/xapi/users/me').reply(200, { admin: false });

    render(<ProgressMonitor onViewResults={mockOnViewResults} />);

    await waitFor(() => {
      const viewButton = screen.getByText(/View Results/i);
      fireEvent.click(viewButton);
    });

    expect(mockOnViewResults).toHaveBeenCalledWith('check-456');
  });

  test('auto-refreshes checks when enabled', async () => {
    mockAxios.onGet('/xapi/filesystem-check/checks/mine').reply(200, [mockRunningCheck]);
    mockAxios.onGet('/xapi/users/me').reply(200, { admin: false });

    render(<ProgressMonitor />);

    await waitFor(() => {
      expect(mockAxios.history.get.filter(r => r.url === '/xapi/filesystem-check/checks/mine').length).toBe(1);
    });

    // Fast-forward 2 seconds
    jest.advanceTimersByTime(2000);

    await waitFor(() => {
      expect(mockAxios.history.get.filter(r => r.url === '/xapi/filesystem-check/checks/mine').length).toBeGreaterThan(1);
    });
  });

  test('cancels check when cancel button clicked', async () => {
    mockAxios.onGet('/xapi/filesystem-check/checks/mine').reply(200, [mockRunningCheck]);
    mockAxios.onGet('/xapi/users/me').reply(200, { admin: false });
    mockAxios.onPost('/xapi/filesystem-check/checks/check-123/cancel').reply(200, {
      status: 'cancelled',
      message: 'Check cancelled'
    });

    global.confirm = jest.fn(() => true);

    render(<ProgressMonitor />);

    await waitFor(() => {
      const cancelButton = screen.getByText(/Cancel Check/i);
      fireEvent.click(cancelButton);
    });

    await waitFor(() => {
      expect(mockAxios.history.post.length).toBe(1);
      expect(mockAxios.history.post[0].url).toBe('/xapi/filesystem-check/checks/check-123/cancel');
    });

    expect(global.confirm).toHaveBeenCalled();
  });

  test('highlights check when highlightCheckId provided', async () => {
    mockAxios.onGet('/xapi/filesystem-check/checks/mine').reply(200, [mockRunningCheck]);
    mockAxios.onGet('/xapi/users/me').reply(200, { admin: false });

    const { container } = render(<ProgressMonitor highlightCheckId="check-123" />);

    await waitFor(() => {
      const highlightedDiv = container.querySelector('.highlighted');
      expect(highlightedDiv).toBeInTheDocument();
    });
  });

  test('admin sees all active checks', async () => {
    const adminCheck = { ...mockRunningCheck, username: 'admin' };

    mockAxios.onGet('/xapi/filesystem-check/checks/mine').reply(200, [mockRunningCheck]);
    mockAxios.onGet('/xapi/filesystem-check/checks/active').reply(200, [adminCheck]);
    mockAxios.onGet('/xapi/users/me').reply(200, { admin: true });

    render(<ProgressMonitor />);

    // Fast-forward to let admin check load
    jest.advanceTimersByTime(100);

    await waitFor(() => {
      expect(screen.getByText(/All Active Checks \(Admin\)/i)).toBeInTheDocument();
    });
  });
});
