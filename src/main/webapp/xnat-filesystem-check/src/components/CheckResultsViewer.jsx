import React, { useState, useEffect } from 'react';
import axios from 'axios';
import moment from 'moment';

const CheckResultsViewer = ({ checkId }) => {
  const [summary, setSummary] = useState(null);
  const [results, setResults] = useState([]);
  const [activeTab, setActiveTab] = useState('summary');
  const [currentPage, setCurrentPage] = useState(0);
  const [pageSize] = useState(100);
  const [total, setTotal] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [filterStatus, setFilterStatus] = useState('');

  useEffect(() => {
    loadSummary();
  }, [checkId]);

  useEffect(() => {
    if (activeTab !== 'summary') {
      loadResults();
    }
  }, [activeTab, currentPage, filterStatus]);

  const loadSummary = async () => {
    try {
      const response = await axios.get(`/xapi/filesystem-check/checks/${checkId}/summary`);
      setSummary(response.data);
      setError(null);
    } catch (err) {
      console.error('Error loading summary:', err);
      setError('Failed to load check summary');
    }
  };

  const loadResults = async () => {
    setLoading(true);
    try {
      const params = {
        page: currentPage,
        size: pageSize
      };

      if (filterStatus) {
        params.status = filterStatus;
      }

      const response = await axios.get(`/xapi/filesystem-check/checks/${checkId}/results`, { params });

      setResults(response.data.results || []);
      setTotal(response.data.total || 0);
      setTotalPages(response.data.totalPages || 0);
      setError(null);
    } catch (err) {
      console.error('Error loading results:', err);
      setError('Failed to load results');
    } finally {
      setLoading(false);
    }
  };

  const exportCSV = (status = '') => {
    const url = `/xapi/filesystem-check/checks/${checkId}/export/csv${status ? `?status=${status}` : ''}`;
    window.open(url, '_blank');
  };

  const handleTabChange = (tab, status = '') => {
    setActiveTab(tab);
    setFilterStatus(status);
    setCurrentPage(0);
  };

  const handlePageChange = (newPage) => {
    if (newPage >= 0 && newPage < totalPages) {
      setCurrentPage(newPage);
    }
  };

  if (!summary) {
    return (
      <div className="check-results-viewer">
        <div className="alert alert-info">
          <i className="fa fa-spinner fa-spin"></i> Loading check results...
        </div>
      </div>
    );
  }

  const statusCounts = summary.statusCounts || {};

  return (
    <div className="check-results-viewer">
      <div className="results-header">
        <h4>
          Filesystem Check Results
          <span className={`status-badge status-${summary.status}`}>
            {summary.status}
          </span>
        </h4>
        {summary.startedAt && (
          <p className="text-muted">
            Started: {moment(summary.startedAt).format('YYYY-MM-DD HH:mm:ss')}
            {summary.completedAt && (
              <span> | Completed: {moment(summary.completedAt).format('YYYY-MM-DD HH:mm:ss')}</span>
            )}
          </p>
        )}

        <div className="export-buttons" style={{ marginBottom: '15px' }}>
          <button className="btn btn-sm btn-default" onClick={() => exportCSV()}>
            <i className="fa fa-download"></i> Export All (CSV)
          </button>
          {statusCounts.missing > 0 && (
            <button className="btn btn-sm btn-danger" onClick={() => exportCSV('missing')}  style={{ marginLeft: '5px' }}>
              <i className="fa fa-download"></i> Export Missing Only
            </button>
          )}
          {statusCounts.unresolved > 0 && (
            <button className="btn btn-sm btn-warning" onClick={() => exportCSV('unresolved')} style={{ marginLeft: '5px' }}>
              <i className="fa fa-download"></i> Export Unresolved Only
            </button>
          )}
        </div>
      </div>

      {error && (
        <div className="alert alert-danger">
          <i className="fa fa-exclamation-triangle"></i> {error}
        </div>
      )}

      <ul className="nav nav-tabs">
        <li className={activeTab === 'summary' ? 'active' : ''}>
          <a onClick={() => handleTabChange('summary')}>Summary</a>
        </li>
        <li className={activeTab === 'missing' ? 'active' : ''}>
          <a onClick={() => handleTabChange('missing', 'missing')}>
            Missing Files
            {statusCounts.missing > 0 && (
              <span className="badge badge-danger">{statusCounts.missing}</span>
            )}
          </a>
        </li>
        <li className={activeTab === 'found' ? 'active' : ''}>
          <a onClick={() => handleTabChange('found', 'found')}>
            Found Files
            {statusCounts.found > 0 && (
              <span className="badge badge-success">{statusCounts.found}</span>
            )}
          </a>
        </li>
        <li className={activeTab === 'unresolved' ? 'active' : ''}>
          <a onClick={() => handleTabChange('unresolved', 'unresolved')}>
            Unresolved
            {statusCounts.unresolved > 0 && (
              <span className="badge badge-warning">{statusCounts.unresolved}</span>
            )}
          </a>
        </li>
      </ul>

      <div className="tab-content">
        {activeTab === 'summary' && (
          <div className="tab-pane active" style={{ padding: '15px' }}>
            <div className="summary-stats">
              <div className="stat-group">
                <h5>Overall Statistics</h5>
                <table className="table table-condensed">
                  <tbody>
                    <tr>
                      <td>Total Files Checked</td>
                      <td className="text-right"><strong>{summary.totalFiles || 0}</strong></td>
                    </tr>
                    <tr>
                      <td>Files Processed</td>
                      <td className="text-right">{summary.processedFiles || 0}</td>
                    </tr>
                    <tr>
                      <td>Files Found</td>
                      <td className="text-right text-success"><strong>{summary.filesFound || 0}</strong></td>
                    </tr>
                    <tr>
                      <td>Files Missing</td>
                      <td className={`text-right ${summary.filesMissing > 0 ? 'text-danger' : 'text-success'}`}>
                        <strong>{summary.filesMissing || 0}</strong>
                      </td>
                    </tr>
                    <tr>
                      <td>Files Unresolved</td>
                      <td className={`text-right ${summary.filesUnresolved > 0 ? 'text-warning' : 'text-success'}`}>
                        <strong>{summary.filesUnresolved || 0}</strong>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>

              {(summary.filesMissing === 0 && summary.filesUnresolved === 0) && (
                <div className="alert alert-success">
                  <i className="fa fa-check-circle"></i> All files verified successfully! No issues found.
                </div>
              )}

              {summary.filesMissing > 0 && (
                <div className="alert alert-danger">
                  <i className="fa fa-exclamation-triangle"></i> Found {summary.filesMissing} missing file(s). Click "Missing Files" tab to view details.
                </div>
              )}

              {summary.filesUnresolved > 0 && (
                <div className="alert alert-warning">
                  <i className="fa fa-exclamation-circle"></i> Found {summary.filesUnresolved} unresolved file reference(s). Click "Unresolved" tab to view details.
                </div>
              )}
            </div>
          </div>
        )}

        {activeTab !== 'summary' && (
          <div className="tab-pane active" style={{ padding: '15px' }}>
            {loading ? (
              <div className="alert alert-info">
                <i className="fa fa-spinner fa-spin"></i> Loading results...
              </div>
            ) : results.length === 0 ? (
              <div className="alert alert-info">
                No {filterStatus} files found.
              </div>
            ) : (
              <>
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
                        <th>Path</th>
                        {filterStatus === 'error' && <th>Error</th>}
                      </tr>
                    </thead>
                    <tbody>
                      {results.map((file, index) => (
                        <tr key={index}>
                          <td>{file.project}</td>
                          <td>{file.session}</td>
                          <td>{file.resource}</td>
                          <td><span className="label label-default">{file.scope}</span></td>
                          <td>{file.scanId || file.assessorId || '-'}</td>
                          <td>{file.fileName}</td>
                          <td className="path-cell" title={file.filePath}>
                            {file.filePath || '-'}
                          </td>
                          {filterStatus === 'error' && <td>{file.errorMessage}</td>}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>

                <div className="pagination-controls" style={{ marginTop: '15px', textAlign: 'center' }}>
                  <div className="btn-group">
                    <button
                      className="btn btn-default"
                      onClick={() => handlePageChange(0)}
                      disabled={currentPage === 0}
                    >
                      <i className="fa fa-angle-double-left"></i> First
                    </button>
                    <button
                      className="btn btn-default"
                      onClick={() => handlePageChange(currentPage - 1)}
                      disabled={currentPage === 0}
                    >
                      <i className="fa fa-angle-left"></i> Previous
                    </button>
                    <button className="btn btn-default" disabled>
                      Page {currentPage + 1} of {totalPages} ({total} total)
                    </button>
                    <button
                      className="btn btn-default"
                      onClick={() => handlePageChange(currentPage + 1)}
                      disabled={currentPage >= totalPages - 1}
                    >
                      Next <i className="fa fa-angle-right"></i>
                    </button>
                    <button
                      className="btn btn-default"
                      onClick={() => handlePageChange(totalPages - 1)}
                      disabled={currentPage >= totalPages - 1}
                    >
                      Last <i className="fa fa-angle-double-right"></i>
                    </button>
                  </div>
                </div>
              </>
            )}
          </div>
        )}
      </div>
    </div>
  );
};

export default CheckResultsViewer;
