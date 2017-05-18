package nl.hsac.fitnesse.junit.allure;

import fitnesse.junit.FitNessePageAnnotation;
import fitnesse.junit.FitNesseRunner;
import fitnesse.wiki.WikiPage;
import nl.hsac.fitnesse.junit.HsacFitNesseRunner;
import org.apache.commons.io.FilenameUtils;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import ru.yandex.qatools.allure.Allure;
import ru.yandex.qatools.allure.config.AllureModelUtils;
import ru.yandex.qatools.allure.events.ClearStepStorageEvent;
import ru.yandex.qatools.allure.events.MakeAttachmentEvent;
import ru.yandex.qatools.allure.events.TestCaseFailureEvent;
import ru.yandex.qatools.allure.events.TestCaseFinishedEvent;
import ru.yandex.qatools.allure.events.TestCaseStartedEvent;
import ru.yandex.qatools.allure.events.TestSuiteFinishedEvent;
import ru.yandex.qatools.allure.events.TestSuiteStartedEvent;
import ru.yandex.qatools.allure.model.Label;
import ru.yandex.qatools.allure.utils.AnnotationManager;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JUnit listener for Allure Framework. Based on default ru.yandex.qatools.allure.junit.AllureRunListener
 */
public class JUnitAllureFrameworkListener extends RunListener {
    private static final String SCREENSHOT_EXT = "png";
    private static final String PAGESOURCE_EXT = "html";
    private static final Pattern SCREENSHOT_PATTERN = Pattern.compile("href=\"([^\"]*." + SCREENSHOT_EXT + ")\"");
    private static final Pattern PAGESOURCE_PATTERN = Pattern.compile("href=\"([^\"]*." + PAGESOURCE_EXT + ")\"");
    private final HashMap<String, String> suites;
    private final Label hostLabel;
    private final Allure allure;

    public JUnitAllureFrameworkListener() {
        this.allure = Allure.LIFECYCLE;
        this.suites = new HashMap<>();
        String hostName = "unknown";
        try {
            hostName = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
        hostLabel = new Label();
        hostLabel.setName("host");
        hostLabel.setValue(hostName);
    }

    private void testSuiteStarted(Description description) {
        String uid = this.generateSuiteUid(description.getDisplayName());
        String suiteName = System.getProperty(HsacFitNesseRunner.SUITE_OVERRIDE_VARIABLE_NAME);
        if (null == suiteName) {
            suiteName = description.getAnnotation(FitNesseRunner.Suite.class).value();
        }

        TestSuiteStartedEvent event = new TestSuiteStartedEvent(uid, suiteName);
        AnnotationManager am = new AnnotationManager(description.getAnnotations());
        am.update(event);
        event.withLabels(AllureModelUtils.createTestFrameworkLabel("FitNesse"));
        getAllure().fire(event);
    }


    public void testStarted(Description description) {
        FitNessePageAnnotation pageAnn = description.getAnnotation(FitNessePageAnnotation.class);
        if (pageAnn != null) {
            WikiPage page = pageAnn.getWikiPage();
            String suiteName = page.getParent().getName();

            TestCaseStartedEvent event = new TestCaseStartedEvent(this.getSuiteUid(description), description.getMethodName());
            AnnotationManager am = new AnnotationManager(description.getAnnotations());
            am.update(event);

            this.fireClearStepStorage();
            getAllure().fire(event);

            String tagInfo = page.getData().getProperties().get("Suites");
            createStories(suiteName, tagInfo);
        }
    }

    public void testFailure(Failure failure) {
        if (failure.getDescription().isTest()) {
            Throwable exception = failure.getException();
            List<Pattern> patterns = new ArrayList<>();
            patterns.add(SCREENSHOT_PATTERN);
            patterns.add(PAGESOURCE_PATTERN);
            processAttachments(exception, patterns);

            this.fireTestCaseFailure(exception);
            this.recordTestResult(failure.getDescription());

        } else {
            this.startFakeTestCase(failure.getDescription());
            this.fireTestCaseFailure(failure.getException());
            this.finishFakeTestCase();
        }
    }

    public void testAssumptionFailure(Failure failure) {
        this.testFailure(failure);
    }

    public void testFinished(Description description) {
        String methodName = description.getMethodName();
        makeAttachment(fitnesseResult(methodName).getBytes(), "FitNesse Result page", "text/html");
        getAllure().fire(new TestCaseFinishedEvent());
    }

    private void testSuiteFinished(String uid) {
        getAllure().fire(new TestSuiteFinishedEvent(uid));
    }

    public void testRunFinished(Result result) {

        for (String uid : this.getSuites().values()) {
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

    private void startFakeTestCase(Description description) {
        String uid = this.getSuiteUid(description);
        String name = description.isTest() ? description.getMethodName() : description.getClassName();
        TestCaseStartedEvent event = new TestCaseStartedEvent(uid, name);
        AnnotationManager am = new AnnotationManager(description.getAnnotations());
        am.update(event);
        this.fireClearStepStorage();
        getAllure().fire(event);
    }

    private void finishFakeTestCase() {
        getAllure().fire(new TestCaseFinishedEvent());
    }

    private void fireTestCaseFailure(Throwable throwable) {
        getAllure().fire((new TestCaseFailureEvent()).withThrowable(throwable));
    }

    private void fireClearStepStorage() {
        getAllure().fire(new ClearStepStorageEvent());
    }

    private Allure getAllure() {
        return this.allure;
    }

    public Map<String, String> getSuites() {
        return this.suites;
    }

    private void recordTestResult(Description description) {
        this.testFinished(description);
    }

    private void processAttachments(Throwable ex, List<Pattern> patterns) {
        for (Pattern pattern : patterns) {
            if (null != ex.getMessage()) {
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
        MakeAttachmentEvent ev = new MakeAttachmentEvent(file, attName, type);
        getAllure().fire(ev);
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
        String iFrame = String.format("<iframe src=\"../fitnesseResults/%s.html\" style=\"%s\">", test, style);
        return String.format("<html><head><title>FitNesse Report</title></head><body>%s</body>", iFrame);
    }

    private void createStories(String suite, String tagInfo) {
        List<Label> labels = new ArrayList<>();

        Label featureLabel = new Label();
        featureLabel.setName("feature");
        featureLabel.setValue(suite);
        labels.add(featureLabel);
        if (null != tagInfo) {
            String[] tags = tagInfo.split(",");
            for (String tag : tags) {
                tag = tag.trim();
                Label storyLabel = new Label();
                storyLabel.setName("story");
                storyLabel.setValue(tag);
                labels.add(storyLabel);
            }
        }

        //For some reason, the host label no longer gets set when applying story labels..
        labels.add(hostLabel);

        AllureSetLabelsEvent event = new AllureSetLabelsEvent(labels);
        getAllure().fire(event);
    }
}
