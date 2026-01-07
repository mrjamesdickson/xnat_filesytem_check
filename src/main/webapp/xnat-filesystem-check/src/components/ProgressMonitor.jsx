import React, { useState, useEffect } from 'react';
import axios from 'axios';
import moment from 'moment';

const ProgressMonitor = ({ highlightCheckId, onViewResults }) => {
  const [myChecks, setMyChecks] = useState([]);
  const [activeChecks, setActiveChecks] = useState([]);
  const [isAdmin, setIsAdmin] = useState(false);
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    loadMyChecks();
    checkAdminStatus();

    if (autoRefresh) {
      const interval = setInterval(() => {
        loadMyChecks();
        if (isAdmin) {
          loadActiveChecks();
        }
      }, 2000); // Refresh every 2 seconds

      return () => clearInterval(interval);
    }
  }, [autoRefresh, isAdmin]);

  const loadMyChecks = async () => {
    try {
      const response = await axios.get('/xapi/filesystem-check/checks/mine');
      setMyChecks(response.data);
      setError(null);
    } catch (err) {
      console.error('Error loading my checks:', err);
      setError('Failed to load check progress');
    }
  };

  const loadActiveChecks = async () => {
    try {
      const response = await axios.get('/xapi/filesystem-check/checks/active');
      setActiveChecks(response.data);
    } catch (err) {
      console.error('Error loading active checks:', err);
    }
  };

  const checkAdminStatus = async () => {
    try {
      const response = await axios.get('/xapi/users/me');
      setIsAdmin(response.data.admin || false);
    } catch (err) {
      console.error('Error checking admin status:', err);
    }
  };

  const cancelCheck = async (checkId) => {
    if (!confirm('Are you sure you want to cancel this check?')) {
      return;
    }

    try {
      await axios.post(`/xapi/filesystem-check/checks/${checkId}/cancel`);
      loadMyChecks();
    } catch (err) {
      console.error('Error cancelling check:', err);
      alert('Failed to cancel check: ' + (err.response?.data?.message || err.message));
    }
  };

  const renderProgress = (progress) => {
    const isRunning = progress.status === 'running' || progress.status === 'queued';
    const percentComplete = progress.percentComplete || 0;
    const isHighlighted = progress.checkId === highlightCheckId;

    return (
      <div
        key={progress.checkId}
        className={`progress-item ${isHighlighted ? 'highlighted' : ''}`}
        style={isHighlighted ? { border: '2px solid #5bc0de', backgroundColor: '#f0f9ff' } : {}}
      >
        <div className="progress-header">
          <h5>
            Check ID: {progress.checkId.substring(0, 8)}...
            <span className={`status-badge status-${progress.status}`}>
              {progress.status}
            </span>
          </h5>
          <div className="progress-meta">
            <span>Started: {moment(progress.startedAt).format('YYYY-MM-DD HH:mm:ss')}</span>
            {progress.completedAt && (
              <span> | Completed: {moment(progress.completedAt).format('YYYY-MM-DD HH:mm:ss')}</span>
            )}
            {isAdmin && progress.username && (
              <span> | User: {progress.username}</span>
            )}
          </div>
        </div>

        {isRunning && (
          <>
            <div className="progress-bar-container">
              <div
                className="progress-bar"
                style={{ width: `${percentComplete}%` }}
              >
                {Math.round(percentComplete)}%
              </div>
            </div>

            <div className="progress-details">
              {progress.currentProject && (
                <div>Current Project: <strong>{progress.currentProject}</strong></div>
              )}
              {progress.currentSession && (
                <div>Current Session: <strong>{progress.currentSession}</strong></div>
              )}

              <div className="progress-stats">
                <span>Projects: {progress.processedProjects}/{progress.totalProjects || '?'}</span>
                <span>Sessions: {progress.processedSessions}/{progress.totalSessions || '?'}</span>
                <span>Files: {progress.processedFiles}/{progress.totalFiles || '?'}</span>
              </div>

              <div className="progress-results">
                <span className="found">Found: {progress.filesFound || 0}</span>
                <span className="missing">Missing: {progress.filesMissing || 0}</span>
                <span className="unresolved">Unresolved: {progress.filesUnresolved || 0}</span>
              </div>
            </div>

            <button
              className="btn btn-sm btn-warning"
              onClick={() => cancelCheck(progress.checkId)}
            >
              <i className="fa fa-stop"></i> Cancel Check
            </button>
          </>
        )}

        {!isRunning && progress.message && (
          <div className={`alert alert-${progress.status === 'completed' ? 'success' : 'warning'}`}>
            {progress.message}
          </div>
        )}

        {progress.status === 'completed' && (
          <>
            <div className="completed-stats">
              <div>Total Files Checked: <strong>{progress.processedFiles}</strong></div>
              <div>Files Found: <span className="text-success">{progress.filesFound}</span></div>
              <div>Files Missing: <span className="text-danger">{progress.filesMissing}</span></div>
              <div>Files Unresolved: <span className="text-warning">{progress.filesUnresolved}</span></div>
            </div>
            {onViewResults && (
              <button
                className="btn btn-sm btn-primary"
                onClick={() => onViewResults(progress.checkId)}
                style={{ marginTop: '10px' }}
              >
                <i className="fa fa-eye"></i> View Results
              </button>
            )}
          </>
        )}
      </div>
    );
  };

  return (
    <div className="progress-monitor">
      <div className="panel panel-default">
        <div className="panel-heading">
          <h4 className="panel-title">
            Filesystem Check Progress Monitor
            <div className="pull-right">
              <label className="auto-refresh-toggle">
                <input
                  type="checkbox"
                  checked={autoRefresh}
                  onChange={(e) => setAutoRefresh(e.target.checked)}
                />
                Auto-refresh
              </label>
            </div>
          </h4>
        </div>

        <div className="panel-body">
          {error && (
            <div className="alert alert-danger">
              <i className="fa fa-exclamation-triangle"></i> {error}
            </div>
          )}

          <h4>My Checks</h4>
          {myChecks.length === 0 ? (
            <p className="text-muted">No checks in progress or history</p>
          ) : (
            <div className="checks-list">
              {myChecks.sort((a, b) =>
                new Date(b.startedAt) - new Date(a.startedAt)
              ).map(renderProgress)}
            </div>
          )}

          {isAdmin && activeChecks.length > 0 && (
            <>
              <hr />
              <h4>All Active Checks (Admin)</h4>
              <div className="checks-list">
                {activeChecks.sort((a, b) =>
                  new Date(b.startedAt) - new Date(a.startedAt)
                ).map(renderProgress)}
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  );
};

export default ProgressMonitor;
