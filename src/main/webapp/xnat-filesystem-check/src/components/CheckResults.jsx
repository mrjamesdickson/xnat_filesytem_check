import React, { useState } from 'react';
import moment from 'moment';

const CheckResults = ({ report }) => {
  const [activeTab, setActiveTab] = useState('summary');
  const [expandedResources, setExpandedResources] = useState(new Set());

  if (!report) return null;

  const stats = report.stats || {};
  const missingFiles = report.missingFiles || [];
  const unresolvedFiles = report.unresolvedFiles || [];
  const resourceDetails = report.resourceDetails || [];

  const toggleResource = (index) => {
    const newExpanded = new Set(expandedResources);
    if (newExpanded.has(index)) {
      newExpanded.delete(index);
    } else {
      newExpanded.add(index);
    }
    setExpandedResources(newExpanded);
  };

  const downloadReport = (format) => {
    const filename = `xnat_filesystem_check_${moment().format('YYYYMMDD_HHmmss')}.${format}`;
    let content, mimeType;

    if (format === 'json') {
      content = JSON.stringify(report, null, 2);
      mimeType = 'application/json';
    } else if (format === 'csv') {
      // Generate CSV for missing files
      const headers = ['Project', 'Session', 'Resource', 'Scope', 'Scan', 'Assessor', 'File', 'Path'];
      const rows = missingFiles.map(f => [
        f.project, f.session, f.resource, f.scope,
        f.scan || '', f.assessor || '', f.file, f.path
      ]);
      content = [headers, ...rows].map(row => row.join(',')).join('\n');
      mimeType = 'text/csv';
    }

    const blob = new Blob([content], { type: mimeType });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  };

  const getStatusClass = (value, isError = false) => {
    if (isError) {
      return value > 0 ? 'text-danger' : 'text-success';
    }
    return '';
  };

  return (
    <div className="check-results">
      <div className="results-header">
        <h4>
          Filesystem Check Results
          <span className={`status-badge ${report.status === 'completed' ? 'success' : 'error'}`}>
            {report.status}
          </span>
        </h4>
        {report.generatedAt && (
          <p className="text-muted">
            Generated: {moment(report.generatedAt).format('YYYY-MM-DD HH:mm:ss')}
          </p>
        )}

        <div className="download-buttons">
          <button className="btn btn-sm btn-default" onClick={() => downloadReport('json')}>
            <i className="fa fa-download"></i> Download JSON
          </button>
          <button className="btn btn-sm btn-default" onClick={() => downloadReport('csv')}>
            <i className="fa fa-download"></i> Download CSV
          </button>
        </div>
      </div>

      <ul className="nav nav-tabs">
        <li className={activeTab === 'summary' ? 'active' : ''}>
          <a onClick={() => setActiveTab('summary')}>Summary</a>
        </li>
        <li className={activeTab === 'missing' ? 'active' : ''}>
          <a onClick={() => setActiveTab('missing')}>
            Missing Files
            {missingFiles.length > 0 && (
              <span className="badge badge-danger">{missingFiles.length}</span>
            )}
          </a>
        </li>
        <li className={activeTab === 'unresolved' ? 'active' : ''}>
          <a onClick={() => setActiveTab('unresolved')}>
            Unresolved
            {unresolvedFiles.length > 0 && (
              <span className="badge badge-warning">{unresolvedFiles.length}</span>
            )}
          </a>
        </li>
        <li className={activeTab === 'resources' ? 'active' : ''}>
          <a onClick={() => setActiveTab('resources')}>Resource Details</a>
        </li>
      </ul>

      <div className="tab-content">
        {activeTab === 'summary' && (
          <div className="tab-pane active">
            <div className="summary-stats">
              <div className="stat-group">
                <h5>Projects & Sessions</h5>
                <table className="table table-condensed">
                  <tbody>
                    <tr>
                      <td>Projects</td>
                      <td className="text-right">{stats.projects || 0}</td>
                    </tr>
                    <tr>
                      <td>Sessions</td>
                      <td className="text-right">{stats.sessions || 0}</td>
                    </tr>
                    <tr>
                      <td>Scans</td>
                      <td className="text-right">{stats.scans || 0}</td>
                    </tr>
                    <tr>
                      <td>Assessors</td>
                      <td className="text-right">{stats.assessors || 0}</td>
                    </tr>
                  </tbody>
                </table>
              </div>

              <div className="stat-group">
                <h5>Filesystem Results</h5>
                <table className="table table-condensed">
                  <tbody>
                    <tr>
                      <td>Files Checked</td>
                      <td className="text-right">{stats.files_total || 0}</td>
                    </tr>
                    <tr>
                      <td>Files Found</td>
                      <td className="text-right text-success">{stats.files_found || 0}</td>
                    </tr>
                    <tr>
                      <td>Files Missing</td>
                      <td className={`text-right ${getStatusClass(stats.files_missing, true)}`}>
                        {stats.files_missing || 0}
                      </td>
                    </tr>
                    <tr>
                      <td>Files Unresolved</td>
                      <td className={`text-right ${getStatusClass(stats.files_unresolved, true)}`}>
                        {stats.files_unresolved || 0}
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>

              <div className="stat-group">
                <h5>Resources</h5>
                <table className="table table-condensed">
                  <tbody>
                    <tr>
                      <td>Total Resources</td>
                      <td className="text-right">{stats.resources || 0}</td>
                    </tr>
                    <tr>
                      <td>Session Resources</td>
                      <td className="text-right">{stats.session_resources || 0}</td>
                    </tr>
                    <tr>
                      <td>Scan Resources</td>
                      <td className="text-right">{stats.scan_resources || 0}</td>
                    </tr>
                    <tr>
                      <td>Assessor Resources</td>
                      <td className="text-right">{stats.assessor_resources || 0}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        )}

        {activeTab === 'missing' && (
          <div className="tab-pane active">
            {missingFiles.length === 0 ? (
              <div className="alert alert-success">
                <i className="fa fa-check-circle"></i> No missing files found!
              </div>
            ) : (
              <div className="table-responsive">
                <table className="table table-striped table-condensed">
                  <thead>
                    <tr>
                      <th>Project</th>
                      <th>Session</th>
                      <th>Resource</th>
                      <th>Scope</th>
                      <th>Scan/Assessor</th>
                      <th>Path</th>
                    </tr>
                  </thead>
                  <tbody>
                    {missingFiles.map((file, index) => (
                      <tr key={index}>
                        <td>{file.project}</td>
                        <td>{file.session}</td>
                        <td>{file.resource}</td>
                        <td><span className="label label-default">{file.scope}</span></td>
                        <td>{file.scan || file.assessor || '-'}</td>
                        <td className="path-cell" title={file.path}>
                          {file.path}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        )}

        {activeTab === 'unresolved' && (
          <div className="tab-pane active">
            {unresolvedFiles.length === 0 ? (
              <div className="alert alert-success">
                <i className="fa fa-check-circle"></i> All files were resolved!
              </div>
            ) : (
              <div className="table-responsive">
                <table className="table table-striped table-condensed">
                  <thead>
                    <tr>
                      <th>Project</th>
                      <th>Session</th>
                      <th>Resource</th>
                      <th>Scope</th>
                      <th>Scan/Assessor</th>
                      <th>File</th>
                      <th>Error</th>
                    </tr>
                  </thead>
                  <tbody>
                    {unresolvedFiles.map((file, index) => (
                      <tr key={index}>
                        <td>{file.project}</td>
                        <td>{file.session}</td>
                        <td>{file.resource}</td>
                        <td><span className="label label-default">{file.scope}</span></td>
                        <td>{file.scan || file.assessor || '-'}</td>
                        <td>{file.file}</td>
                        <td>{file.error || 'Unable to resolve path'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        )}

        {activeTab === 'resources' && (
          <div className="tab-pane active">
            <div className="table-responsive">
              <table className="table table-striped table-condensed">
                <thead>
                  <tr>
                    <th>Project</th>
                    <th>Session</th>
                    <th>Resource</th>
                    <th>Scope</th>
                    <th>Status</th>
                    <th>Files</th>
                    <th>Found</th>
                    <th>Missing</th>
                    <th>Unresolved</th>
                  </tr>
                </thead>
                <tbody>
                  {resourceDetails.map((resource, index) => (
                    <tr key={index} className={resource.status === 'error' ? 'danger' : ''}>
                      <td>{resource.project}</td>
                      <td>{resource.session}</td>
                      <td>{resource.resource}</td>
                      <td><span className="label label-default">{resource.scope}</span></td>
                      <td>
                        <span className={`label label-${resource.status === 'ok' ? 'success' : 'danger'}`}>
                          {resource.status}
                        </span>
                      </td>
                      <td className="text-right">{resource.filesListed || 0}</td>
                      <td className="text-right text-success">{resource.filesFound || 0}</td>
                      <td className={`text-right ${getStatusClass(resource.filesMissing, true)}`}>
                        {resource.filesMissing || 0}
                      </td>
                      <td className={`text-right ${getStatusClass(resource.filesUnresolved, true)}`}>
                        {resource.filesUnresolved || 0}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

export default CheckResults;
