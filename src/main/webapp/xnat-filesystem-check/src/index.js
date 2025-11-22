import React from 'react';
import ReactDOM from 'react-dom';
import FilesystemCheckApp from './components/FilesystemCheckApp';
import './styles/main.css';

// Initialize the XNAT Filesystem Check plugin
window.XNATFilesystemCheck = {
  init: function(containerId) {
    const container = document.getElementById(containerId);
    if (container) {
      ReactDOM.render(<FilesystemCheckApp />, container);
    }
  }
};

export default window.XNATFilesystemCheck;
