# Testing Guide

This document explains how to run all tests for the XNAT Filesystem Check Plugin.

## Test Coverage

The plugin includes comprehensive test coverage:

### Java Backend Tests
- **Unit Tests**: Service layer, DAOs, and business logic
- **Integration Tests**: REST API endpoints
- **End-to-End Tests**: Selenium browser automation

### React Frontend Tests
- **Component Tests**: All React components with Jest and React Testing Library
- **Integration Tests**: User workflows and axios mocking

## Running Tests

### Java Tests

#### Run all Java tests:
```bash
./gradlew test
```

#### Run specific test class:
```bash
./gradlew test --tests FilesystemCheckRestApiTest
```

#### Run tests with coverage:
```bash
./gradlew test jacocoTestReport
```

Coverage report will be generated at: `build/reports/jacoco/test/html/index.html`

### React/JavaScript Tests

#### Run all Jest tests:
```bash
cd src/main/webapp/xnat-filesystem-check
npm test
```

#### Run tests in watch mode:
```bash
npm run test:watch
```

#### Run tests with coverage:
```bash
npm run test:coverage
```

Coverage report will be generated at: `coverage/lcov-report/index.html`

### Selenium End-to-End Tests

#### Prerequisites:
1. XNAT instance running (default: http://localhost:8080)
2. Filesystem Check Plugin installed
3. Valid admin credentials

#### Run E2E tests:
```bash
./gradlew test --tests FilesystemCheckE2ETest
```

#### Configure test environment:
```bash
./gradlew test --tests FilesystemCheckE2ETest \
  -Dxnat.url=http://localhost:8080 \
  -Dxnat.user=admin \
  -Dxnat.password=admin
```

#### Run E2E tests with visible browser (non-headless):
Modify `FilesystemCheckE2ETest.java` and remove the `--headless` option from ChromeOptions.

## Test Structure

```
src/test/java/org/nrg/xnat/plugin/filesystemcheck/
├── services/
│   └── AsyncFilesystemCheckServiceTest.java    # Service layer tests
├── repositories/
│   └── FilesystemCheckDaoTest.java              # DAO tests
├── rest/
│   └── FilesystemCheckRestApiTest.java          # REST API tests
└── e2e/
    └── FilesystemCheckE2ETest.java              # Selenium E2E tests

src/main/webapp/xnat-filesystem-check/src/components/
├── FilesystemCheckApp.test.jsx                  # Main app tests
├── ProgressMonitor.test.jsx                     # Progress monitor tests
└── CheckResultsViewer.test.jsx                  # Results viewer tests
```

## Test Scenarios Covered

### Backend Tests

**AsyncFilesystemCheckService:**
- Check cancellation
- Null check entity handling
- Status updates during processing

**FilesystemCheckDao:**
- Finding active checks
- Finding checks by username
- Deleting old completed checks

**FilesystemCheckRestApi:**
- Starting checks and receiving checkId
- Retrieving check status
- Paginated results retrieval
- Summary statistics
- CSV export
- Check cancellation
- Service status endpoint

### Frontend Tests

**FilesystemCheckApp:**
- Component rendering
- Project loading on mount
- Starting checks
- Error handling
- Button enable/disable logic
- Progress monitor toggle

**ProgressMonitor:**
- Empty state display
- Running check display with progress bar
- Completed check display with statistics
- View Results button functionality
- Auto-refresh functionality
- Check cancellation
- Admin view of all active checks
- Check highlighting

**CheckResultsViewer:**
- Loading state
- Summary statistics display
- Success message for perfect checks
- Tab switching (missing, found, unresolved)
- Pagination controls
- Page navigation
- CSV export (all, missing only, unresolved only)
- Error handling

### E2E Tests

**User Workflows:**
- Login to XNAT
- Navigate to plugin
- Start check for entire archive
- View progress monitor
- View completed check results
- Export results to CSV
- Cancel running check

## Continuous Integration

### GitHub Actions Example

```yaml
name: Tests

on: [push, pull_request]

jobs:
  backend-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - uses: actions/setup-java@v2
        with:
          java-version: '11'
      - name: Run backend tests
        run: ./gradlew test

  frontend-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - uses: actions/setup-node@v2
        with:
          node-version: '16'
      - name: Install dependencies
        run: cd src/main/webapp/xnat-filesystem-check && npm install
      - name: Run frontend tests
        run: cd src/main/webapp/xnat-filesystem-check && npm test
```

## Mocking and Test Data

### Backend Mocks
- **Mockito**: Used for mocking services, DAOs, and XNAT components
- **Test entities**: Sample FilesystemCheckEntity and FileCheckResultEntity

### Frontend Mocks
- **axios-mock-adapter**: Mocks HTTP requests
- **Jest mocks**: Mocks window.confirm, window.alert, window.open

## Troubleshooting

### Java Tests

**Issue**: Tests fail with "Cannot find class"
- **Solution**: Run `./gradlew clean test` to rebuild

**Issue**: Mockito errors
- **Solution**: Ensure mockito-inline is included for static mocking

### React Tests

**Issue**: "Cannot find module"
- **Solution**: Run `npm install` in the React directory

**Issue**: Tests timeout
- **Solution**: Increase Jest timeout in package.json

### Selenium Tests

**Issue**: ChromeDriver not found
- **Solution**: WebDriverManager should download automatically. Check internet connection.

**Issue**: XNAT not accessible
- **Solution**: Ensure XNAT is running at configured URL

**Issue**: Element not found
- **Solution**: Increase TIMEOUT_SECONDS or check element selectors

## Code Coverage Goals

- **Backend**: > 70% line coverage
- **Frontend**: > 80% component coverage
- **E2E**: Critical user workflows covered

## Writing New Tests

### Backend Test Template:
```java
@RunWith(MockitoJUnitRunner.class)
public class MyServiceTest {
    @Mock
    private MyDependency dependency;

    @InjectMocks
    private MyService service;

    @Test
    public void testMyFeature() {
        // Given
        when(dependency.doSomething()).thenReturn(value);

        // When
        Result result = service.myMethod();

        // Then
        assertEquals(expected, result);
        verify(dependency).doSomething();
    }
}
```

### Frontend Test Template:
```javascript
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';
import axios from 'axios';

const mockAxios = new MockAdapter(axios);

describe('MyComponent', () => {
    beforeEach(() => {
        mockAxios.reset();
    });

    test('my feature works', async () => {
        // Given
        mockAxios.onGet('/api/endpoint').reply(200, mockData);

        // When
        render(<MyComponent />);

        // Then
        await waitFor(() => {
            expect(screen.getByText('Expected')).toBeInTheDocument();
        });
    });
});
```

## Resources

- [JUnit 4 Documentation](https://junit.org/junit4/)
- [Mockito Documentation](https://javadoc.io/doc/org.mockito/mockito-core/latest/org/mockito/Mockito.html)
- [React Testing Library](https://testing-library.com/docs/react-testing-library/intro/)
- [Jest Documentation](https://jestjs.io/docs/getting-started)
- [Selenium Documentation](https://www.selenium.dev/documentation/)
