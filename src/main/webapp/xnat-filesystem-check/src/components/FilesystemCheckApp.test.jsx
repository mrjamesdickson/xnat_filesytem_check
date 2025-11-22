import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
import axios from 'axios';
import MockAdapter from 'axios-mock-adapter';
import FilesystemCheckApp from './FilesystemCheckApp';

const mockAxios = new MockAdapter(axios);

describe('FilesystemCheckApp', () => {
  beforeEach(() => {
    mockAxios.reset();
  });

  afterEach(() => {
    jest.clearAllMocks();
  });

  test('renders the main component with title', () => {
    mockAxios.onGet('/xapi/projects').reply(200, { ResultSet: { Result: [] } });

    render(<FilesystemCheckApp />);

    expect(screen.getByText('XNAT Filesystem Check')).toBeInTheDocument();
    expect(screen.getByText(/Read-Only Operation/i)).toBeInTheDocument();
  });

  test('loads projects on mount', async () => {
    const mockProjects = [
      { ID: 'PROJECT1', name: 'Test Project 1' },
      { ID: 'PROJECT2', name: 'Test Project 2' }
    ];

    mockAxios.onGet('/xapi/projects').reply(200, {
      ResultSet: { Result: mockProjects }
    });

    render(<FilesystemCheckApp />);

    await waitFor(() => {
      expect(mockAxios.history.get.length).toBe(1);
      expect(mockAxios.history.get[0].url).toBe('/xapi/projects');
    });
  });

  test('starts check and receives checkId', async () => {
    mockAxios.onGet('/xapi/projects').reply(200, { ResultSet: { Result: [] } });
    mockAxios.onPost('/xapi/filesystem-check/check/archive').reply(200, {
      checkId: 'test-check-123',
      status: 'queued'
    });

    render(<FilesystemCheckApp />);

    // Enable entire archive checkbox
    const archiveCheckbox = screen.getByLabelText(/Check Entire Archive/i);
    fireEvent.click(archiveCheckbox);

    // Click start check button
    const startButton = screen.getByText(/Start Filesystem Check/i);
    fireEvent.click(startButton);

    await waitFor(() => {
      expect(mockAxios.history.post.length).toBe(1);
      expect(mockAxios.history.post[0].url).toBe('/xapi/filesystem-check/check/archive');
    });

    await waitFor(() => {
      expect(screen.getByText(/Filesystem check started successfully/i)).toBeInTheDocument();
    });
  });

  test('shows error message on failed check start', async () => {
    mockAxios.onGet('/xapi/projects').reply(200, { ResultSet: { Result: [] } });
    mockAxios.onPost('/xapi/filesystem-check/check/archive').reply(500, {
      message: 'Server error'
    });

    render(<FilesystemCheckApp />);

    const archiveCheckbox = screen.getByLabelText(/Check Entire Archive/i);
    fireEvent.click(archiveCheckbox);

    const startButton = screen.getByText(/Start Filesystem Check/i);
    fireEvent.click(startButton);

    await waitFor(() => {
      expect(screen.getByText(/Failed to start filesystem check/i)).toBeInTheDocument();
    });
  });

  test('disables start button when no projects selected and archive not checked', () => {
    mockAxios.onGet('/xapi/projects').reply(200, { ResultSet: { Result: [] } });

    render(<FilesystemCheckApp />);

    const startButton = screen.getByText(/Start Filesystem Check/i);
    expect(startButton).toBeDisabled();
  });

  test('toggles progress monitor visibility', async () => {
    mockAxios.onGet('/xapi/projects').reply(200, { ResultSet: { Result: [] } });

    render(<FilesystemCheckApp />);

    const toggleButton = screen.getByText(/Show Progress Monitor/i);
    fireEvent.click(toggleButton);

    await waitFor(() => {
      expect(screen.getByText(/Hide Progress Monitor/i)).toBeInTheDocument();
    });
  });
});
