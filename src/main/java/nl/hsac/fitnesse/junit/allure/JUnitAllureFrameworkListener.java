package nl.hsac.fitnesse.junit.allure;

import fitnesse.junit.FitNessePageAnnotation;
import fitnesse.wiki.WikiPage;
import io.qameta.allure.Allure;
import io.qameta.allure.AllureLifecycle;
import io.qameta.allure.model.Label;
import io.qameta.allure.model.Status;
import io.qameta.allure.model.TestResult;
import io.qameta.allure.model.TestResultContainer;
import io.qameta.allure.util.ResultsUtils;
import nl.hsac.fitnesse.fixture.Environment;
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
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.qameta.allure.util.ResultsUtils.getStatus;
import static io.qameta.allure.util.ResultsUtils.getStatusDetails;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * JUnit listener for Allure Framework. Based on default io.qameta.allure.junit4.AllureJunit4
 */

public class JUnitAllureFrameworkListener extends RunListener {
    private static final String SCREENSHOT_EXT = "png";
    private static final String PAGESOURCE_EXT = "html";
    private static final Pattern SCREENSHOT_PATTERN = Pattern.compile("href=\"([^\"]*." + SCREENSHOT_EXT + ")\"");
    private static final Pattern PAGESOURCE_PATTERN = Pattern.compile("href=\"([^\"]*." + PAGESOURCE_EXT + ")\"");
    private static final Pattern SPECIAL_PAGE_PATTERN = Pattern.compile(".*(\\.SuiteSetUp|\\.SuiteTearDown)$");

    private final Environment hsacEnvironment = Environment.getInstance();
    private String currentTestUUID;
    private final LinkedHashMap<String, String> suites;
    private final Label hostLabel;
    private final AllureLifecycle lifecycle;
    private final boolean skipSpecialPages;

    public JUnitAllureFrameworkListener() {
        this.lifecycle = Allure.getLifecycle();
        this.suites = new LinkedHashMap<>();
        hostLabel = ResultsUtils.createHostLabel();
        skipSpecialPages = null != System.getProperty("skipSpecialPagesInAllure") ?
                Boolean.valueOf(System.getProperty("skipSpecialPagesInAllure")) : false;
    }

    private void testSuiteStarted(String suiteName) {
        String uid = this.generateSuiteUid(suiteName);
        final TestResultContainer result = new TestResultContainer()
                .setUuid(uid)
                .setName(suiteName)
                .setStart(System.currentTimeMillis());
        getLifecycle().startTestContainer(result);
    }

    private String generateNewTestUUID() {
        currentTestUUID = UUID.randomUUID().toString();
        return currentTestUUID;
    }

    @Override
    public void testStarted(Description description) {
        if (reportTestPage(description.getMethodName())) {
            FitNessePageAnnotation pageAnn = description.getAnnotation(FitNessePageAnnotation.class);
            if (pageAnn != null) {
                final String uuid = generateNewTestUUID();
                final TestResult result = createTestResult(pageAnn, uuid, description);
                getLifecycle().scheduleTestCase(result);
                getLifecycle().startTestCase(uuid);
            }
        }
    }

    @Override
    public void testFailure(Failure failure) {
        Description description = failure.getDescription();
        if (reportTestPage(description.getMethodName())) {
            String uuid;
            if (description.isTest()) {
                uuid = currentTestUUID;
                processAttachments(failure.getException(), SCREENSHOT_PATTERN, PAGESOURCE_PATTERN);
            } else {
                uuid = startFakeTestCase(description);
            }
            fireTestCaseFailure(uuid, failure.getException());
            finishTestCase(uuid);
        }
    }

    @Override
    public void testAssumptionFailure(Failure failure) {
        this.testFailure(failure);
    }

    @Override
    public void testFinished(Description description) {
        if (reportTestPage(description.getMethodName())) {
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
        String suiteName;
        FitNessePageAnnotation pageAnn = description.getAnnotation(FitNessePageAnnotation.class);
        if (pageAnn != null) {
            suiteName = getFullSuitePath(pageAnn.getWikiPage());
        } else {
            suiteName = description.getClassName();
        }
        if (!this.getSuites().containsKey(suiteName)) {
            this.testSuiteStarted(suiteName);
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
                .setUuid(uid)
                .setHistoryId(getHistoryId(description, methodName))
                .setName(name)
                .setFullName(fullName);

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
                .setStatus(getStatus(throwable).orElse(null))
                .setStatusDetails(getStatusDetails(throwable).orElse(null))
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
                    String filePath = hsacEnvironment.getFitNesseRootDir() + "/" + patternMatcher.group(1);
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
        String iFrame = String.format("<iframe src=\"../../fitnesseResults/%s.html\" style=\"%s\">", test, style);
        return String.format("<html><head><title>FitNesse Report</title></head><body>%s</body>", iFrame);
    }

    private TestResult createTestResult(FitNessePageAnnotation pageAnn, String uuid, Description description) {
        WikiPage page = pageAnn.getWikiPage();
        String fullName = getFullName(page);

        String suiteName = page.getParent().getName();
        String[] tagInfo = getTags(page);
        List<Label> labels = createLabels(suiteName, tagInfo);

        String name = page.getName();

        return new TestResult()
                .setUuid(uuid)
                .setHistoryId(getHistoryId(description, fullName))
                .setName(name)
                .setFullName(fullName)
                .setLabels(labels);
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

    private List<Label> createLabels(String suite, String[] tags) {
        List<Label> labels = new ArrayList<>();

        Label featureLabel = ResultsUtils.createFeatureLabel(suite);
        labels.add(featureLabel);
        for (String tag : tags) {
            tag = tag.trim();
            Label storyLabel = ResultsUtils.createStoryLabel(tag);
            labels.add(storyLabel);
            Label tagLabel = ResultsUtils.createTagLabel(tag);
            labels.add(tagLabel);
        }
        //For some reason, the host label no longer gets set when applying story labels..
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
            return MessageDigest.getInstance("md5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Could not find md5 hashing algorithm", e);
        }
    }

    private String[] getTags(WikiPage page) {
        String[] tags = new String[0];
        String tagInfo = page.getData().getProperties().get("Suites");
        if (null != tagInfo) {
            tags = tagInfo.split(",");
        }
        return tags;
    }

    private String getFullSuitePath(WikiPage page) {
        StringBuilder suitePath = new StringBuilder();
        while (page.getParent() != page) {
            if (!page.getParent().getName().equals("FitNesseRoot")) {
                suitePath.insert(0, page.getParent().getName() + ".");
            }
            page = page.getParent();
        }
        return suitePath.toString().substring(0, suitePath.length() - 1);
    }

    private boolean reportTestPage(String pageName) {
        return !skipSpecialPages || !SPECIAL_PAGE_PATTERN.matcher(pageName).matches();
    }
}
