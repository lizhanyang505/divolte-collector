package io.divolte.browser;

import com.google.common.base.Preconditions;
import com.saucelabs.common.SauceOnDemandAuthentication;
import com.saucelabs.common.SauceOnDemandSessionIdProvider;
import com.saucelabs.junit.ConcurrentParameterized;
import com.saucelabs.junit.SauceOnDemandTestWatcher;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.remote.CapabilityType;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.RemoteWebDriver;

import java.net.URL;
import java.util.LinkedList;

import static org.junit.Assert.assertEquals;

/**
 * JUnit test that runs tests against Sauce Labs using multiple browsers in parallel.
 */
@RunWith(ConcurrentParameterized.class)
public class SaucelabsJsEventIntegrationTest implements SauceOnDemandSessionIdProvider {

    public SauceOnDemandAuthentication auth = new SauceOnDemandAuthentication(
            System.getProperty("sauselabs.username"),
            System.getProperty("sauselabs.accesskey"));

    /**
     * JUnit Rule which will mark the Sauce Job as passed/failed.
     */
    @Rule
    public SauceOnDemandTestWatcher resultReportingTestWatcher = new SauceOnDemandTestWatcher(this, auth);

    /*
     * Os, Browser and version settings.
     */
    private String browser;
    private String os;
    private String version;

    private WebDriver driver;
    private String sessionId;

    /**
     * Constructs a new instance of the test.  The constructor requires three string parameters, which represent the operating
     * system, version and browser to be used when launching a Sauce VM.  The order of the parameters should be the same
     * as that of the elements within the {@link #browsersStrings()} method.
     */
    public SaucelabsJsEventIntegrationTest(String os, String browser, String version) {
        super();
        this.os = os;
        this.version = version;
        this.browser = browser;
    }

    /**
     * Constructor arguments specifing the os/browser/version for the concurrent tests.
     */
    @ConcurrentParameterized.Parameters
    public static LinkedList browsersStrings() {
        LinkedList<String[]> browsers = new LinkedList<>();
        browsers.add(new String[]{"Windows 8.1", "internet explorer", "11"});
//        browsers.add(new String[]{"OSX 10.8", "safari", "6"});
        //TODO go nuts with devices!
        return browsers;
    }

    /**
     * Constructs a new {@link RemoteWebDriver} instance which is configured to use the capabilities defined by the {@link #browser},
     * {@link #version} and {@link #os} instance variables, and which is configured to run against ondemand.saucelabs.com, using
     * the username and access key populated by the {@link #auth} instance.
     *
     * @throws Exception if an error occurs during the creation of the {@link RemoteWebDriver} instance.
     */
    @Before
    public void setUp() throws Exception {
        DesiredCapabilities capabilities = new DesiredCapabilities();

        capabilities.setCapability(CapabilityType.BROWSER_NAME, browser);
        if (version != null) {
            capabilities.setCapability(CapabilityType.VERSION, version);
        }
        capabilities.setCapability(CapabilityType.PLATFORM, os);
        capabilities.setCapability("name", getClass().getSimpleName());
        //TODO explore capabilities.

        Preconditions.checkNotNull(auth.getUsername(), "saucelabs.username not provided as property");
        Preconditions.checkNotNull(auth.getAccessKey(), "saucelabs.accesskey not provided as property");

        URL remoteAddress = new URL("http://" + auth.getUsername() + ":" + auth.getAccessKey() + "@ondemand.saucelabs.com:80/wd/hub");

        this.driver = new RemoteWebDriver( remoteAddress, capabilities);
        this.sessionId = (((RemoteWebDriver) driver).getSessionId()).toString();
    }

    @After
    public void tearDown() throws Exception {
        driver.quit();
    }

    /**
     * Runs a simple test verifying the title of the amazon.com homepage.
     * @throws Exception
     */
    @Test
    public void amazon() throws Exception {
        driver.get("http://www.amazon.com/");
        assertEquals("Amazon.com: Online Shopping for Electronics, Apparel, Computers, Books, DVDs & more", driver.getTitle());
    }


    @Override
    public String getSessionId() {
        return sessionId;
    }
}
