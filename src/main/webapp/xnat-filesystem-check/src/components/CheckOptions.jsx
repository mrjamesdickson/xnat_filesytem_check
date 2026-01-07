import React from 'react';

const CheckOptions = ({
  maxFiles,
  onMaxFilesChange,
  verifyCatalogs,
  onVerifyCatalogsChange,
  disabled
}) => {
  return (
    <div className="check-options">
      <h4>Options</h4>

      <div className="form-group">
        <label htmlFor="maxFiles">
          Maximum Files to Check (optional)
          <small className="text-muted"> - Leave empty to check all files</small>
        </label>
        <input
          type="number"
          id="maxFiles"
          className="form-control"
          value={maxFiles || ''}
          onChange={(e) => onMaxFilesChange(e.target.value ? parseInt(e.target.value) : null)}
          placeholder="No limit"
          min="1"
          disabled={disabled}
        />
      </div>

      <div className="form-group">
        <label className="checkbox">
          <input
            type="checkbox"
            checked={verifyCatalogs}
            onChange={(e) => onVerifyCatalogsChange(e.target.checked)}
            disabled={disabled}
          />
          Verify Catalog Files
          <small className="text-muted"> - Also check catalog.xml files for consistency</small>
        </label>
      </div>
    </div>
  );
};

export default CheckOptions;
