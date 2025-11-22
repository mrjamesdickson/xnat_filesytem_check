import React from 'react';

const ProjectSelector = ({
  projects,
  selectedProjects,
  onSelectionChange,
  entireArchive,
  onEntireArchiveChange,
  disabled
}) => {
  const handleProjectChange = (projectId) => {
    const newSelection = selectedProjects.includes(projectId)
      ? selectedProjects.filter(id => id !== projectId)
      : [...selectedProjects, projectId];
    onSelectionChange(newSelection);
  };

  const handleSelectAll = () => {
    onSelectionChange(projects.map(p => p.id || p.ID));
  };

  const handleClearAll = () => {
    onSelectionChange([]);
  };

  return (
    <div className="project-selector">
      <h4>Select Projects</h4>

      <div className="form-group">
        <label className="checkbox-inline">
          <input
            type="checkbox"
            checked={entireArchive}
            onChange={(e) => onEntireArchiveChange(e.target.checked)}
            disabled={disabled}
          />
          Check Entire Archive
        </label>
      </div>

      {!entireArchive && (
        <div className="project-list">
          <div className="project-list-actions">
            <button
              className="btn btn-sm btn-default"
              onClick={handleSelectAll}
              disabled={disabled}
            >
              Select All
            </button>
            <button
              className="btn btn-sm btn-default"
              onClick={handleClearAll}
              disabled={disabled}
            >
              Clear All
            </button>
            <span className="selection-count">
              {selectedProjects.length} of {projects.length} selected
            </span>
          </div>

          <div className="project-checkbox-list">
            {projects.map((project) => {
              const projectId = project.id || project.ID;
              const projectName = project.name || project.Name || projectId;
              return (
                <label key={projectId} className="checkbox">
                  <input
                    type="checkbox"
                    checked={selectedProjects.includes(projectId)}
                    onChange={() => handleProjectChange(projectId)}
                    disabled={disabled}
                  />
                  <strong>{projectId}</strong>
                  {projectName !== projectId && <span> - {projectName}</span>}
                </label>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
};

export default ProjectSelector;
