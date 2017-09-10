package nl.hsac.fitnesse.junit.allure;

import fitnesse.junit.FitNessePageAnnotation;
import fitnesse.junit.FitNesseRunner;
import fitnesse.wiki.WikiPage;
import io.qameta.allure.Allure;
import io.qameta.allure.AllureLifecycle;
import io.qameta.allure.model.Label;
import io.qameta.allure.model.Status;
import io.qameta.allure.model.TestResult;
import io.qameta.allure.model.TestResultContainer;
import io.qameta.allure.util.ResultsUtils;
import nl.hsac.fitnesse.junit.HsacFitNesseRunner;
import org.apache.commons.io.FilenameUtils;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Stack;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.qameta.allure.junit4.AllureJunit4.MD_5;
import static io.qameta.allure.util.ResultsUtils.getStatus;
import static io.qameta.allure.util.ResultsUtils.getStatusDetails;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * JUnit listener for Allure Framework. Based on default ru.yandex.qatools.lifecycle.junit.AllureRunListener
 */
public class JUnitAllureFrameworkListener extends RunListener {
    private static final String SCREENSHOT_EXT = "png";
    private static final String PAGESOURCE_EXT = "html";
    private static final Pattern SCREENSHOT_PATTERN = Pattern.compile("href=\"([^\"]*." + SCREENSHOT_EXT + ")\"");
    private static final Pattern PAGESOURCE_PATTERN = Pattern.compile("href=\"([^\"]*." + PAGESOURCE_EXT + ")\"");
    private String currentTestUUID;
    private final LinkedHashMap<String, String> suites;
    private final Label hostLabel;
    private final AllureLifecycle lifecycle;

    public JUnitAllureFrameworkListener() {
        this.lifecycle = Allure.getLifecycle();
        this.suites = new LinkedHashMap<>();
        hostLabel = ResultsUtils.createHostLabel();
    }

    private void testSuiteStarted(Description description) {
        String uid = this.generateSuiteUid(description.getDisplayName());
        String suiteName = System.getProperty(HsacFitNesseRunner.SUITE_OVERRIDE_VARIABLE_NAME);
        if (null == suiteName) {
            suiteName = description.getAnnotation(FitNesseRunner.Suite.class).value();
        }

        final TestResultContainer result = new TestResultContainer()
                .withUuid(uid)
                .withName(suiteName)
                .withStart(System.currentTimeMillis());
        getLifecycle().startTestContainer(result);
    }

    private String generateNewTestUUID() {
        currentTestUUID = UUID.randomUUID().toString();
        return currentTestUUID;
    }

    @Override
    public void testStarted(Description description) {
        FitNessePageAnnotation pageAnn = description.getAnnotation(FitNessePageAnnotation.class);
        if (pageAnn != null) {
            final String uuid = generateNewTestUUID();
            final TestResult result = createTestResult(pageAnn, uuid, description);
            getLifecycle().scheduleTestCase(result);
            getLifecycle().startTestCase(uuid);
        }
    }

    @Override
    public void testFailure(Failure failure) {
        String uuid;
        if (failure.getDescription().isTest()) {
            uuid = currentTestUUID;
            processAttachments(failure.getException(), SCREENSHOT_PATTERN, PAGESOURCE_PATTERN);
        } else {
            uuid = startFakeTestCase(failure.getDescription());
        }
        fireTestCaseFailure(uuid, failure.getException());
        finishTestCase(uuid);
    }

    @Override
    public void testAssumptionFailure(Failure failure) {
        this.testFailure(failure);
    }

    @Override
    public void testFinished(Description description) {
        String uuid = currentTestUUID;
        getLifecycle().updateTestCase(uuid, testResult -> {
            if (testResult.getStatus() == null) {
                testResult.setStatus(Status.PASSED);
            }
        });
        String methodName = description.getMethodName();
        makeAttachment(fitnesseResult(methodName).getBytes(), "FitNesse Result page", "text/html");

        finishTestCase(uuid);
    }

    private void testSuiteFinished(String uid) {
        getLifecycle().stopTestContainer(uid);
        getLifecycle().writeTestContainer(uid);
    }

    @Override
    public void testRunFinished(Result result) {
        for (String uid : getSuites().values()) {
            this.testSuiteFinished(uid);
        }
    }

    private String generateSuiteUid(String suiteName) {
        String uid = UUID.randomUUID().toString();
        synchronized (this.getSuites()) {
            this.getSuites().put(suiteName, uid);
            return uid;
        }
    }


    private String getSuiteUid(Description description) {
        String suiteName = description.getClassName();
        if (!this.getSuites().containsKey(suiteName)) {
            Description suiteDescription = Description.createSuiteDescription(description.getTestClass());
            this.testSuiteStarted(suiteDescription);
        }

        return this.getSuites().get(suiteName);
    }

    private String startFakeTestCase(Description description) {
        String uid = this.getSuiteUid(description);
        String name = description.isTest() ? description.getMethodName() : description.getClassName();
        final String className = description.getClassName();
        final String methodName = description.getMethodName();
        final String fullName = Objects.nonNull(methodName) ? String.format("%s.%s", className, methodName) : className;

        TestResult result = new TestResult()
                .withUuid(uid)
                .withHistoryId(getHistoryId(description, methodName))
                .withName(name)
                .withFullName(fullName);

        getLifecycle().scheduleTestCase(result);
        getLifecycle().startTestCase(uid);
        return uid;
    }

    private void finishTestCase(String uuid) {
        getLifecycle().stopTestCase(uuid);
        getLifecycle().writeTestCase(uuid);
    }

    private void fireTestCaseFailure(String uuid, Throwable throwable) {
        getLifecycle().updateTestCase(uuid, testResult -> testResult
                .withStatus(getStatus(throwable).orElse(null))
                .withStatusDetails(getStatusDetails(throwable).orElse(null))
        );
    }

    private AllureLifecycle getLifecycle() {
        return this.lifecycle;
    }

    private Map<String, String> getSuites() {
        return this.suites;
    }

    private void processAttachments(Throwable ex, Pattern... patterns) {
        if (null != ex.getMessage()) {
            for (Pattern pattern : patterns) {
                Matcher patternMatcher = pattern.matcher(ex.getMessage());
                if (patternMatcher.find()) {
                    String filePath = HsacFitNesseRunner.FITNESSE_RESULTS_PATH + "/" + patternMatcher.group(1);
                    String attName;
                    String type;
                    String ext = FilenameUtils.getExtension(Paths.get(filePath).toString());
                    if (ext.equalsIgnoreCase(SCREENSHOT_EXT)) {
                        attName = "Page Screenshot";
                        type = "image/png";
                    } else if (ext.equalsIgnoreCase(PAGESOURCE_EXT)) {
                        attName = "Page Source";
                        type = "text/html";
                    } else {
                        attName = "Attachment";
                        type = "text/html";
                    }
                    makeAttachment(fileToAttach(filePath), attName, type);
                }
            }
        }
    }

    private void makeAttachment(byte[] file, String attName, String type) {
        getLifecycle().addAttachment(attName, type, "", file);
    }

    private byte[] fileToAttach(String filePath) {
        Path path = Paths.get(filePath);
        byte[] data;
        try {
            data = Files.readAllBytes(path);
        } catch (IOException var5) {
            System.err.println("file not found: " + path.toString());
            data = null;
        }
        return data;
    }

    private String fitnesseResult(String test) {
        String style = "width: 99%; height: 99%; overflow: auto; border: 0px;";
        String iFrame = String.format("<iframe src=\"../../../fitnesse-results/%s.html\" style=\"%s\">", test, style);
        return String.format("<html><head><title>FitNesse Report</title></head><body>%s</body>", iFrame);
    }

    private TestResult createTestResult(FitNessePageAnnotation pageAnn, String uuid, Description description) {
        WikiPage page = pageAnn.getWikiPage();
        String fullName = getFullName(page);

        String suiteName = page.getParent().getName();
        String tagInfo = page.getData().getProperties().get("Suites");
        List<Label> labels = createStories(suiteName, tagInfo);

        String name = page.getName();

        return new TestResult()
                    .withUuid(uuid)
                    .withHistoryId(getHistoryId(description, fullName))
                    .withName(name)
                    .withFullName(fullName)
                    .withLabels(labels);
    }

    private String getFullName(WikiPage page) {
        Stack<String> pages = new Stack<>();
        WikiPage p = page;
        while (!p.isRoot()) {
            pages.push(p.getName());
            p = p.getParent();
        }
        return String.join(".", pages);
    }

    private List<Label> createStories(String suite, String tagInfo) {
        List<Label> labels = new ArrayList<>();

        Label featureLabel = ResultsUtils.createFeatureLabel(suite);
        labels.add(featureLabel);
        if (null != tagInfo) {
            String[] tags = tagInfo.split(",");
            for (String tag : tags) {
                tag = tag.trim();
                Label storyLabel = ResultsUtils.createStoryLabel(tag);
                labels.add(storyLabel);
            }
        }

        labels.add(hostLabel);
        labels.add(ResultsUtils.createThreadLabel());
        return labels;
    }

    private String getHistoryId(final Description description, String fullName) {
        return md5(description.getClassName() + fullName);
    }

    private String md5(final String source) {
        final byte[] bytes = getMessageDigest().digest(source.getBytes(UTF_8));
        return new BigInteger(1, bytes).toString(16);
    }

    private MessageDigest getMessageDigest() {
        try {
            return MessageDigest.getInstance(MD_5);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Could not find md5 hashing algorithm", e);
        }
    }
}