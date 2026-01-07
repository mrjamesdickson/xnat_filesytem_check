import '@testing-library/jest-dom';

// Mock window.confirm and window.alert
global.confirm = jest.fn(() => true);
global.alert = jest.fn();

// Mock window.open
global.open = jest.fn();
