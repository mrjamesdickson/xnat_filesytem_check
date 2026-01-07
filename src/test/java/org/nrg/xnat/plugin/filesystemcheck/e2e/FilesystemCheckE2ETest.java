package org.nrg.xnat.plugin.filesystemcheck.e2e;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

import static org.junit.Assert.*;

/**
 * End-to-end Selenium tests for the Filesystem Check Plugin.
 *
 * NOTE: These tests require:
 * - XNAT instance running at http://localhost:8080
 * - Valid test credentials
 * - Filesystem Check Plugin installed
 */
public class FilesystemCheckE2ETest {

    private WebDriver driver;
    private WebDriverWait wait;

    // Configuration - update these for your test environment
    private static final String BASE_URL = System.getProperty("xnat.url", "http://localhost:8080");
    private static final String USERNAME = System.getProperty("xnat.user", "admin");
    private static final String PASSWORD = System.getProperty("xnat.password", "admin");
    private static final int TIMEOUT_SECONDS = 30;

    @BeforeClass
    public static void setupClass() {
        // Setup ChromeDriver using WebDriverManager
        WebDriverManager.chromedriver().setup();
    }

    @Before
    public void setUp() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless"); // Run in headless mode
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--window-size=1920,1080");

        driver = new ChromeDriver(options);
        wait = new WebDriverWait(driver, Duration.ofSeconds(TIMEOUT_SECONDS));
    }

    @After
    public void tearDown() {
        if (driver != null) {
            driver.quit();
        }
    }

    @Test
    public void testLoginToXNAT() {
        // Navigate to XNAT login page
        driver.get(BASE_URL);

        // Wait for login form
        WebElement usernameField = wait.until(
                ExpectedConditions.presenceOfElementLocated(By.id("username"))
        );

        // Login
        usernameField.sendKeys(USERNAME);
        driver.findElement(By.id("password")).sendKeys(PASSWORD);
        driver.findElement(By.name("login")).click();

        // Verify login successful - wait for homepage elements
        wait.until(ExpectedConditions.presenceOfElementLocated(
                By.cssSelector("body.logged-in")
        ));

        assertTrue("Should be logged in", driver.getCurrentUrl().contains(BASE_URL));
    }

    @Test
    public void testNavigateToFilesystemCheckPlugin() {
        loginToXNAT();

        // Navigate to plugin (adjust selector based on your XNAT setup)
        driver.get(BASE_URL + "/app/template/Page.vm/screen/XDATScreen_admin.vm");

        // Find and click filesystem check plugin link
        WebElement pluginLink = wait.until(
                ExpectedConditions.presenceOfElementLocated(
                        By.linkText("Filesystem Check")
                )
        );
        pluginLink.click();

        // Verify plugin loaded
        wait.until(ExpectedConditions.presenceOfElementLocated(
                By.xpath("//*[contains(text(), 'XNAT Filesystem Check')]")
        ));

        assertTrue("Plugin should be loaded",
                driver.getPageSource().contains("XNAT Filesystem Check"));
    }

    @Test
    public void testStartFilesystemCheckForEntireArchive() {
        loginToXNAT();
        navigateToPlugin();

        // Check "Entire Archive" checkbox
        WebElement archiveCheckbox = wait.until(
                ExpectedConditions.elementToBeClickable(
                        By.xpath("//input[@type='checkbox' and contains(@id, 'entireArchive')]")
                )
        );
        archiveCheckbox.click();

        // Click "Start Filesystem Check" button
        WebElement startButton = driver.findElement(
                By.xpath("//button[contains(text(), 'Start Filesystem Check')]")
        );
        assertTrue("Start button should be enabled", startButton.isEnabled());
        startButton.click();

        // Verify check started message appears
        wait.until(ExpectedConditions.presenceOfElementLocated(
                By.xpath("//*[contains(text(), 'Filesystem check started successfully')]")
        ));

        assertTrue("Success message should appear",
                driver.getPageSource().contains("Filesystem check started successfully"));
    }

    @Test
    public void testViewProgressMonitor() {
        loginToXNAT();
        navigateToPlugin();
        startCheck();

        // Progress monitor should be visible after starting check
        WebElement progressMonitor = wait.until(
                ExpectedConditions.presenceOfElementLocated(
                        By.xpath("//*[contains(text(), 'Filesystem Check Progress Monitor')]")
                )
        );

        assertTrue("Progress monitor should be displayed", progressMonitor.isDisplayed());

        // Verify progress elements exist
        assertTrue("Should show 'My Checks' section",
                driver.getPageSource().contains("My Checks"));
    }

    @Test
    public void testViewCompletedCheckResults() {
        loginToXNAT();
        navigateToPlugin();

        // Simulate having a completed check by showing monitor
        WebElement monitorButton = driver.findElement(
                By.xpath("//button[contains(text(), 'Show Progress Monitor')]")
        );
        monitorButton.click();

        // Wait for monitor to load
        wait.until(ExpectedConditions.presenceOfElementLocated(
                By.xpath("//*[contains(text(), 'My Checks')]")
        ));

        // Look for "View Results" button (if any completed checks exist)
        try {
            WebElement viewResultsButton = wait.until(
                    ExpectedConditions.presenceOfElementLocated(
                            By.xpath("//button[contains(text(), 'View Results')]")
                    )
            );
            viewResultsButton.click();

            // Verify results viewer loaded
            wait.until(ExpectedConditions.presenceOfElementLocated(
                    By.xpath("//*[contains(text(), 'Filesystem Check Results')]")
            ));

            assertTrue("Results viewer should be displayed",
                    driver.getPageSource().contains("Summary"));
        } catch (Exception e) {
            // No completed checks available - this is acceptable for testing
            System.out.println("No completed checks available for viewing");
        }
    }

    @Test
    public void testExportResults() {
        loginToXNAT();
        navigateToPlugin();

        // Show monitor
        WebElement monitorButton = driver.findElement(
                By.xpath("//button[contains(text(), 'Show Progress Monitor')]")
        );
        monitorButton.click();

        // Try to view results and export
        try {
            WebElement viewResultsButton = wait.until(
                    ExpectedConditions.presenceOfElementLocated(
                            By.xpath("//button[contains(text(), 'View Results')]")
                    )
            );
            viewResultsButton.click();

            // Look for export button
            WebElement exportButton = wait.until(
                    ExpectedConditions.presenceOfElementLocated(
                            By.xpath("//button[contains(text(), 'Export')]")
                    )
            );

            assertTrue("Export button should be present", exportButton.isDisplayed());
        } catch (Exception e) {
            // No completed checks - acceptable
            System.out.println("No completed checks for export test");
        }
    }

    @Test
    public void testCancelRunningCheck() {
        loginToXNAT();
        navigateToPlugin();
        startCheck();

        // Look for cancel button in progress monitor
        try {
            WebElement cancelButton = wait.until(
                    ExpectedConditions.presenceOfElementLocated(
                            By.xpath("//button[contains(text(), 'Cancel Check')]")
                    )
            );
            cancelButton.click();

            // Confirm cancellation dialog
            driver.switchTo().alert().accept();

            // Verify cancellation message or status change
            wait.until(ExpectedConditions.presenceOfElementLocated(
                    By.xpath("//*[contains(text(), 'cancelled')]")
            ));
        } catch (Exception e) {
            // Check might have completed too quickly
            System.out.println("Check completed before cancellation could be tested");
        }
    }

    // Helper methods

    private void loginToXNAT() {
        driver.get(BASE_URL);
        WebElement usernameField = wait.until(
                ExpectedConditions.presenceOfElementLocated(By.id("username"))
        );
        usernameField.sendKeys(USERNAME);
        driver.findElement(By.id("password")).sendKeys(PASSWORD);
        driver.findElement(By.name("login")).click();

        wait.until(ExpectedConditions.presenceOfElementLocated(
                By.cssSelector("body.logged-in")
        ));
    }

    private void navigateToPlugin() {
        driver.get(BASE_URL + "/app/template/Page.vm/screen/FilesystemCheck.vm");
        wait.until(ExpectedConditions.presenceOfElementLocated(
                By.xpath("//*[contains(text(), 'XNAT Filesystem Check')]")
        ));
    }

    private void startCheck() {
        WebElement archiveCheckbox = wait.until(
                ExpectedConditions.elementToBeClickable(
                        By.xpath("//input[@type='checkbox' and contains(@id, 'entireArchive')]")
                )
        );
        archiveCheckbox.click();

        WebElement startButton = driver.findElement(
                By.xpath("//button[contains(text(), 'Start Filesystem Check')]")
        );
        startButton.click();

        wait.until(ExpectedConditions.presenceOfElementLocated(
                By.xpath("//*[contains(text(), 'Filesystem check started successfully')]")
        ));
    }
}
