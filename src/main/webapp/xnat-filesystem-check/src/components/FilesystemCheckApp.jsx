import React, { useState, useEffect } from 'react';
import axios from 'axios';
import ProjectSelector from './ProjectSelector';
import CheckResults from './CheckResults';
import CheckOptions from './CheckOptions';

const FilesystemCheckApp = () => {
  const [projects, setProjects] = useState([]);
  const [selectedProjects, setSelectedProjects] = useState([]);
  const [entireArchive, setEntireArchive] = useState(false);
  const [maxFiles, setMaxFiles] = useState(null);
  const [verifyCatalogs, setVerifyCatalogs] = useState(false);
  const [isChecking, setIsChecking] = useState(false);
  const [report, setReport] = useState(null);
  const [error, setError] = useState(null);

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

  const performCheck = async () => {
    setIsChecking(true);
    setError(null);
    setReport(null);

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
      setReport(response.data);
    } catch (err) {
      console.error('Error performing check:', err);
      setError(err.response?.data?.message || 'Failed to perform filesystem check');
    } finally {
      setIsChecking(false);
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

  return (
    <div className="filesystem-check-container">
      <div className="panel panel-default">
        <div className="panel-heading">
          <h3 className="panel-title">XNAT Filesystem Check</h3>
        </div>
        <div className="panel-body">
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
            disabled={isChecking}
          />

          <CheckOptions
            maxFiles={maxFiles}
            onMaxFilesChange={setMaxFiles}
            verifyCatalogs={verifyCatalogs}
            onVerifyCatalogsChange={setVerifyCatalogs}
            disabled={isChecking}
          />

          <div className="check-actions">
            <button
              className="btn btn-primary"
              onClick={performCheck}
              disabled={isChecking || (!entireArchive && selectedProjects.length === 0)}
            >
              {isChecking ? (
                <>
                  <i className="fa fa-spinner fa-spin"></i> Checking...
                </>
              ) : (
                <>
                  <i className="fa fa-check-circle"></i> Run Filesystem Check
                </>
              )}
            </button>
          </div>

          {error && (
            <div className="alert alert-danger" role="alert">
              <i className="fa fa-exclamation-triangle"></i> {error}
            </div>
          )}

          {report && <CheckResults report={report} />}
        </div>
      </div>
    </div>
  );
};

export default FilesystemCheckApp;
