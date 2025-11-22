import React, { useState, useEffect } from 'react';
import axios from 'axios';
import ProjectSelector from './ProjectSelector';
import CheckResultsViewer from './CheckResultsViewer';
import CheckOptions from './CheckOptions';
import ProgressMonitor from './ProgressMonitor';

const FilesystemCheckApp = () => {
  const [projects, setProjects] = useState([]);
  const [selectedProjects, setSelectedProjects] = useState([]);
  const [entireArchive, setEntireArchive] = useState(false);
  const [maxFiles, setMaxFiles] = useState(null);
  const [verifyCatalogs, setVerifyCatalogs] = useState(false);
  const [isStarting, setIsStarting] = useState(false);
  const [activeCheckId, setActiveCheckId] = useState(null);
  const [error, setError] = useState(null);
  const [showMonitor, setShowMonitor] = useState(false);
  const [viewingCheckId, setViewingCheckId] = useState(null);

  useEffect(() => {
    loadProjects();
  }, []);

  const loadProjects = async () => {
    try {
      const response = await axios.get('/xapi/projects');
      setProjects(response.data.ResultSet.Result || []);
    } catch (err) {
      console.error('Error loading projects:', err);
      setError('Failed to load projects');
    }
  };

  const startCheck = async () => {
    setIsStarting(true);
    setError(null);
    setViewingCheckId(null);

    try {
      let endpoint;
      let requestData = {
        maxFiles: maxFiles,
        verifyCatalogs: verifyCatalogs
      };

      if (entireArchive) {
        endpoint = '/xapi/filesystem-check/check/archive';
      } else if (selectedProjects.length === 1) {
        endpoint = `/xapi/filesystem-check/check/project/${selectedProjects[0]}`;
      } else {
        endpoint = '/xapi/filesystem-check/check';
        requestData.projectIds = selectedProjects;
        requestData.entireArchive = false;
      }

      const response = await axios.post(endpoint, requestData);

      // New async API returns checkId immediately
      const checkId = response.data.checkId;
      setActiveCheckId(checkId);
      setShowMonitor(true);

      // Show success message
      setError(null);

    } catch (err) {
      console.error('Error starting check:', err);
      setError(err.response?.data?.message || 'Failed to start filesystem check');
    } finally {
      setIsStarting(false);
    }
  };

  const handleProjectSelection = (projectIds) => {
    setSelectedProjects(projectIds);
    if (projectIds.length > 0) {
      setEntireArchive(false);
    }
  };

  const handleEntireArchiveChange = (checked) => {
    setEntireArchive(checked);
    if (checked) {
      setSelectedProjects([]);
    }
  };

  const handleViewResults = (checkId) => {
    setViewingCheckId(checkId);
    setShowMonitor(false);
  };

  const handleBackToMonitor = () => {
    setViewingCheckId(null);
    setShowMonitor(true);
  };

  return (
    <div className="filesystem-check-container">
      <div className="panel panel-default">
        <div className="panel-heading">
          <h3 className="panel-title">
            XNAT Filesystem Check
            <button
              className="btn btn-sm btn-info pull-right"
              onClick={() => {
                setShowMonitor(!showMonitor);
                setViewingCheckId(null);
              }}
            >
              <i className="fa fa-tasks"></i> {showMonitor ? 'Hide' : 'Show'} Progress Monitor
            </button>
          </h3>
        </div>
        <div className="panel-body">
          <div className="alert alert-info">
            <strong><i className="fa fa-info-circle"></i> Read-Only Operation:</strong> This plugin only validates and reports. It never modifies, deletes, or changes any files.
          </div>

          {viewingCheckId ? (
            <>
              <button
                className="btn btn-default btn-sm"
                onClick={handleBackToMonitor}
                style={{ marginBottom: '15px' }}
              >
                <i className="fa fa-arrow-left"></i> Back to Monitor
              </button>
              <CheckResultsViewer checkId={viewingCheckId} />
            </>
          ) : showMonitor ? (
            <ProgressMonitor
              highlightCheckId={activeCheckId}
              onViewResults={handleViewResults}
            />
          ) : (
            <>
              <p className="description">
                Validate that files referenced in XNAT exist on the filesystem.
                Check individual projects or scan the entire archive.
              </p>

              <ProjectSelector
                projects={projects}
                selectedProjects={selectedProjects}
                onSelectionChange={handleProjectSelection}
                entireArchive={entireArchive}
                onEntireArchiveChange={handleEntireArchiveChange}
                disabled={isStarting}
              />

              <CheckOptions
                maxFiles={maxFiles}
                onMaxFilesChange={setMaxFiles}
                verifyCatalogs={verifyCatalogs}
                onVerifyCatalogsChange={setVerifyCatalogs}
                disabled={isStarting}
              />

              <div className="check-actions">
                <button
                  className="btn btn-primary"
                  onClick={startCheck}
                  disabled={isStarting || (!entireArchive && selectedProjects.length === 0)}
                >
                  {isStarting ? (
                    <>
                      <i className="fa fa-spinner fa-spin"></i> Starting Check...
                    </>
                  ) : (
                    <>
                      <i className="fa fa-check-circle"></i> Start Filesystem Check
                    </>
                  )}
                </button>
              </div>

              {error && (
                <div className="alert alert-danger" role="alert">
                  <i className="fa fa-exclamation-triangle"></i> {error}
                </div>
              )}

              {activeCheckId && !showMonitor && (
                <div className="alert alert-success" role="alert">
                  <i className="fa fa-check-circle"></i> Filesystem check started successfully!
                  Check ID: {activeCheckId.substring(0, 8)}...
                  <button
                    className="btn btn-sm btn-info pull-right"
                    onClick={() => setShowMonitor(true)}
                  >
                    View Progress
                  </button>
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
};

export default FilesystemCheckApp;
