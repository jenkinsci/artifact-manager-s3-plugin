/*
 * The MIT License
 *
 * Copyright 2018 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package io.jenkins.plugins.artifact_manager_jclouds;

import org.htmlunit.FailingHttpStatusCodeException;
import hudson.Functions;
import hudson.init.impl.InstallUncaughtExceptionHandler;
import hudson.model.Result;
import hudson.model.Run;
import hudson.tasks.LogRotator;
import io.jenkins.plugins.httpclient.RobustHTTPClient;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import jenkins.model.ArtifactManagerConfiguration;
import jenkins.model.GlobalBuildDiscarderListener;
import org.apache.http.ConnectionClosedException;
import org.apache.http.HttpVersion;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicStatusLine;
import org.jclouds.blobstore.ContainerNotFoundException;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.TimeoutStepExecution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LogRecorder;
import org.jvnet.hudson.test.junit.jupiter.BuildWatcherExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Explores responses to edge cases such as server errors and hangs.
 * We do mocking at the jclouds level, rather than using (say) S3Mock, because:
 * <ul>
 * <li>We are interested here in the behavior of the generic Jenkins integration code in this package,
 *     as well as its dependencies in Jenkins core and Pipeline, not that of a particular blob store.
 * <li>S3-specific failure modes are of interest but are generally hard to simulate using those mock frameworks anyway.
 *     For example, some S3 mock frameworks completely ignore authentication.
 *     Conversely, some failure modes we want to test may not be supported by an S3 mock framework.
 * <li>S3 mock frameworks are not written to expect the jclouds abstraction layer, and vice-versa.
 *     The jclouds {@code AWSS3ProviderMetadata} cannot accept a custom {@code AmazonS3} which {@code S3MockRule} would supply;
 *     it reimplements the S3 REST API from scratch, not using the AWS SDK.
 *     It would be necessary to run Dockerized mocks with local HTTP ports.
 * </ul>
 */
@Issue("JENKINS-50597")
@WithJenkins
class NetworkTest {

    @SuppressWarnings("unused")
    @RegisterExtension
    private static final BuildWatcherExtension BUILD_WATCHER = new BuildWatcherExtension();

    private final LogRecorder logger = new LogRecorder();

    private JenkinsRule j;

    @BeforeEach
    void beforeEach(JenkinsRule rule) throws Exception {
        j = rule;

        MockBlobStore mockBlobStore = new MockBlobStore();
        mockBlobStore.getContext().getBlobStore().createContainerInLocation(null, mockBlobStore.getContainer());
        ArtifactManagerConfiguration.get().getArtifactManagerFactories().add(new JCloudsArtifactManagerFactory(mockBlobStore));

        j.createSlave("remote", null, null);
    }

    @Test
    void unrecoverableErrorArchiving() throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        failIn(BlobStoreProvider.HttpMethod.PUT, "p/1/artifacts/f", 403, 0);
        p.setDefinition(new CpsFlowDefinition("node('remote') {writeFile file: 'f', text: '.'; archiveArtifacts 'f'}", true));
        WorkflowRun b = j.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0));
        j.assertLogContains("ERROR: Failed to upload", b);
        j.assertLogContains("/container/p/1/artifacts/f?…, response: 403 simulated 403 failure, body: Detailed explanation of 403.", b);
        j.assertLogNotContains("Retrying upload", b);
        j.assertLogNotContains("\tat hudson.tasks.ArtifactArchiver.perform", b);
    }

    @Test
    void recoverableErrorArchiving() throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        failIn(BlobStoreProvider.HttpMethod.PUT, "p/1/artifacts/f", 500, 0);
        p.setDefinition(new CpsFlowDefinition("node('remote') {writeFile file: 'f', text: '.'; archiveArtifacts 'f'}", true));
        WorkflowRun b = j.buildAndAssertSuccess(p);
        j.assertLogContains("/container/p/1/artifacts/f?…, response: 500 simulated 500 failure, body: Detailed explanation of 500.", b);
        j.assertLogContains("Retrying upload", b);
        j.assertLogNotContains("\tat hudson.tasks.ArtifactArchiver.perform", b);
    }

    @Test
    void networkExceptionArchiving() throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        failIn(BlobStoreProvider.HttpMethod.PUT, "p/1/artifacts/f", 0, 0);
        p.setDefinition(new CpsFlowDefinition("node('remote') {writeFile file: 'f', text: '.'; archiveArtifacts 'f'}", true));
        WorkflowRun b = j.buildAndAssertSuccess(p);
        // currently prints a ‘java.net.SocketException: Connection reset’ but not sure if we really care
        j.assertLogContains("Retrying upload", b);
        j.assertLogNotContains("\tat hudson.tasks.ArtifactArchiver.perform", b);
    }

    @Test
    void repeatedRecoverableErrorArchiving() throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        JCloudsArtifactManager.client = new RobustHTTPClient();
        JCloudsArtifactManager.client.setStopAfterAttemptNumber(3);
        try {
            failIn(BlobStoreProvider.HttpMethod.PUT, "p/1/artifacts/f", 500, 3);
            p.setDefinition(new CpsFlowDefinition("node('remote') {writeFile file: 'f', text: '.'; archiveArtifacts 'f'}", true));
            WorkflowRun b = j.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0));
            j.assertLogContains("ERROR: Failed to upload", b);
            j.assertLogContains("/container/p/1/artifacts/f?…, response: 500 simulated 500 failure, body: Detailed explanation of 500.", b);
            j.assertLogContains("Retrying upload", b);
            j.assertLogNotContains("\tat hudson.tasks.ArtifactArchiver.perform", b);
        } finally {
            JCloudsArtifactManager.client = new RobustHTTPClient();
        }
    }

    @Test
    void hangArchiving() throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        JCloudsArtifactManager.client = new RobustHTTPClient();
        JCloudsArtifactManager.client.setTimeout(5, TimeUnit.SECONDS);
        try {
            hangIn(BlobStoreProvider.HttpMethod.PUT, "p/1/artifacts/f");
            p.setDefinition(new CpsFlowDefinition("node('remote') {writeFile file: 'f', text: '.'; archiveArtifacts 'f'}", true));
            WorkflowRun b = j.buildAndAssertSuccess(p);
            j.assertLogContains("Retrying upload", b);
            j.assertLogNotContains("\tat hudson.tasks.ArtifactArchiver.perform", b);
            // Also from master:
            hangIn(BlobStoreProvider.HttpMethod.PUT, "p/2/artifacts/f");
            p.setDefinition(new CpsFlowDefinition("node('" + j.jenkins.getSelfLabel().getName() + "') {writeFile file: 'f', text: '.'; archiveArtifacts 'f'}", true));
            b = j.buildAndAssertSuccess(p);
            j.assertLogContains("Retrying upload", b);
            j.assertLogNotContains("\tat hudson.tasks.ArtifactArchiver.perform", b);
        } finally {
            JCloudsArtifactManager.client = new RobustHTTPClient();
        }
    }

    @Test
    void interruptedArchiving() throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        hangIn(BlobStoreProvider.HttpMethod.PUT, "p/1/artifacts/f");
        p.setDefinition(new CpsFlowDefinition("node('remote') {writeFile file: 'f', text: '.'; archiveArtifacts 'f'}", true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage("Archiving artifacts", b);
        Thread.sleep(2000); // wait for hangIn to sleep; OK if occasionally it has not gotten there yet, we still expect the same result
        b.getExecutor().interrupt();
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(b));
        // Currently prints a stack trace of java.lang.InterruptedException; good enough.
        // Check the same from a timeout within the build, rather than a user abort, and also from master just for fun:
        hangIn(BlobStoreProvider.HttpMethod.PUT, "p/2/artifacts/f");
        p.setDefinition(new CpsFlowDefinition("node('" + j.jenkins.getSelfLabel().getName() + "') {writeFile file: 'f', text: '.'; timeout(time: 3, unit: 'SECONDS') {archiveArtifacts 'f'}}", true));
        j.assertLogContains(new TimeoutStepExecution.ExceededTimeout(null).getShortDescription(), j.assertBuildStatus(Result.ABORTED, p.scheduleBuild2(0)));
    }

    @Test
    void unrecoverableErrorUnstashing() throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        failIn(BlobStoreProvider.HttpMethod.GET, "p/1/stashes/f.tgz", 403, 0);
        p.setDefinition(new CpsFlowDefinition("node('remote') {writeFile file: 'f', text: '.'; stash 'f'; unstash 'f'}", true));
        WorkflowRun b = j.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0));
        j.assertLogContains("ERROR: Failed to download", b);
        j.assertLogContains("/container/p/1/stashes/f.tgz?…", b);
        j.assertLogContains("response: 403 simulated 403 failure, body: Detailed explanation of 403.", b);
        j.assertLogNotContains("Retrying download", b);
        j.assertLogNotContains("\tat org.jenkinsci.plugins.workflow.flow.StashManager.unstash", b);
    }

    @Test
    void recoverableErrorUnstashing() throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        failIn(BlobStoreProvider.HttpMethod.GET, "p/1/stashes/f.tgz", 500, 0);
        p.setDefinition(new CpsFlowDefinition("node('remote') {writeFile file: 'f', text: '.'; stash 'f'; unstash 'f'}", true));
        WorkflowRun b = j.buildAndAssertSuccess(p);
        j.assertLogContains("/container/p/1/stashes/f.tgz?…", b);
        j.assertLogContains("response: 500 simulated 500 failure, body: Detailed explanation of 500.", b);
        j.assertLogContains("Retrying download", b);
        j.assertLogNotContains("\tat org.jenkinsci.plugins.workflow.flow.StashManager.unstash", b);
    }

    @Test
    void networkExceptionUnstashing() throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        // failIn does not work: URL connection gets a 200 status despite a ConnectionClosedException being thrown; a new connection is made.
        MockBlobStore.speciallyHandle(BlobStoreProvider.HttpMethod.GET, "p/1/stashes/f.tgz", (request, response, context) -> {});
        p.setDefinition(new CpsFlowDefinition("node('remote') {writeFile file: 'f', text: '.'; stash 'f'; unstash 'f'}", true));
        WorkflowRun b = j.buildAndAssertSuccess(p);
        j.assertLogContains("Retrying download", b);
        // Currently catches an error from FilePath.untarFrom: java.io.IOException: Failed to extract input stream
        j.assertLogNotContains("\tat org.jenkinsci.plugins.workflow.flow.StashManager.unstash", b);
    }

    @Test
    void repeatedRecoverableErrorUnstashing() throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        JCloudsArtifactManager.client = new RobustHTTPClient();
        JCloudsArtifactManager.client.setStopAfterAttemptNumber(3);
        try {
            failIn(BlobStoreProvider.HttpMethod.GET, "p/1/stashes/f.tgz", 500, 3);
            p.setDefinition(new CpsFlowDefinition("node('remote') {writeFile file: 'f', text: '.'; stash 'f'; unstash 'f'}", true));
            WorkflowRun b = j.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0));
            j.assertLogContains("ERROR: Failed to download", b);
            j.assertLogContains("/container/p/1/stashes/f.tgz?…", b);
            j.assertLogContains("response: 500 simulated 500 failure, body: Detailed explanation of 500.", b);
            j.assertLogContains("Retrying download", b);
            j.assertLogNotContains("\tat org.jenkinsci.plugins.workflow.flow.StashManager.unstash", b);
        } finally {
            JCloudsArtifactManager.client = new RobustHTTPClient();
        }
    }

    @Test
    void hangUnstashing() throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        JCloudsArtifactManager.client = new RobustHTTPClient();
        JCloudsArtifactManager.client.setTimeout(5, TimeUnit.SECONDS);
        try {
            hangIn(BlobStoreProvider.HttpMethod.GET, "p/1/stashes/f.tgz");
            p.setDefinition(new CpsFlowDefinition("node('remote') {writeFile file: 'f', text: '.'; stash 'f'; unstash 'f'}", true));
            WorkflowRun b = j.buildAndAssertSuccess(p);
            j.assertLogContains("Retrying download", b);
            j.assertLogNotContains("\tat org.jenkinsci.plugins.workflow.flow.StashManager.unstash", b);
            hangIn(BlobStoreProvider.HttpMethod.GET, "p/2/stashes/f.tgz");
            p.setDefinition(new CpsFlowDefinition("node('" + j.jenkins.getSelfLabel().getName() + "') {writeFile file: 'f', text: '.'; stash 'f'; unstash 'f'}", true));
            b = j.buildAndAssertSuccess(p);
            j.assertLogContains("Retrying download", b);
            j.assertLogNotContains("\tat org.jenkinsci.plugins.workflow.flow.StashManager.unstash", b);
        } finally {
            JCloudsArtifactManager.client = new RobustHTTPClient();
        }
    }

    @Test
    void interruptedUnstashing() throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        hangIn(BlobStoreProvider.HttpMethod.GET, "p/1/stashes/f.tgz");
        p.setDefinition(new CpsFlowDefinition("node('remote') {writeFile file: 'f', text: '.'; stash 'f'; unstash 'f'}", true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[Pipeline] unstash", b);
        Thread.sleep(2000);
        b.getExecutor().interrupt();
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(b));
        hangIn(BlobStoreProvider.HttpMethod.GET, "p/2/stashes/f.tgz");
        p.setDefinition(new CpsFlowDefinition("node('" + j.jenkins.getSelfLabel().getName() + "') {writeFile file: 'f', text: '.'; stash 'f'; timeout(time: 3, unit: 'SECONDS') {unstash 'f'}}", true));
        j.assertLogContains(new TimeoutStepExecution.ExceededTimeout(null).getShortDescription(), j.assertBuildStatus(Result.ABORTED, p.scheduleBuild2(0)));
    }

    @Test
    void recoverableErrorUnarchiving() throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        failIn(BlobStoreProvider.HttpMethod.GET, "p/1/artifacts/f", 500, 0);
        p.setDefinition(new CpsFlowDefinition("node('remote') {writeFile file: 'f', text: '.'; archiveArtifacts 'f'; unarchive mapping: ['f': 'f']}", true));
        WorkflowRun b = j.buildAndAssertSuccess(p);
        j.assertLogContains("/container/p/1/artifacts/f?…", b);
        j.assertLogContains("response: 500 simulated 500 failure, body: Detailed explanation of 500.", b);
        j.assertLogContains("Retrying download", b);
        j.assertLogNotContains("\tat org.jenkinsci.plugins.workflow.steps.ArtifactUnarchiverStepExecution.run", b);
    }

    // TBD if jclouds, or its S3 provider, is capable of differentiating recoverable from nonrecoverable errors. The error simulated here:
    // org.jclouds.blobstore.ContainerNotFoundException: nonexistent.s3.amazonaws.com not found: The specified bucket does not exist
    //     at org.jclouds.s3.handlers.ParseS3ErrorFromXmlContent.refineException(ParseS3ErrorFromXmlContent.java:81)
    //     at org.jclouds.aws.handlers.ParseAWSErrorFromXmlContent.handleError(ParseAWSErrorFromXmlContent.java:89)
    //     at …
    // Disconnecting network in the middle of some operations produces a bunch of warnings from BackoffLimitedRetryHandler.ifReplayableBackoffAndReturnTrue
    // followed by a org.jclouds.http.HttpResponseException: Network is unreachable (connect failed) connecting to GET …
    // Also not testing hangs here since org.jclouds.Constants.PROPERTY_SO_TIMEOUT/PROPERTY_CONNECTION_TIMEOUT probably handle this.
    @Test
    void errorListing() throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        MockApiMetadata.handleGetBlobKeysInsideContainer("container", () -> {throw new ContainerNotFoundException("container", "sorry");});
        p.setDefinition(new CpsFlowDefinition("node('remote') {writeFile file: 'f', text: '.'; archiveArtifacts 'f'; unarchive mapping: ['f': 'f']}", true));
        WorkflowRun b = j.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0));
        j.assertLogContains(ContainerNotFoundException.class.getName(), b);
        // Currently prints a stack trace, OK.
    }

    // Interrupts during a network operation seem to have no effect; when retrying during network disconnection,
    // BackoffLimitedRetryHandler.imposeBackoffExponentialDelay throws InterruptedException wrapped in RuntimeException.
    @Test
    void interruptedListing() throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        MockApiMetadata.handleGetBlobKeysInsideContainer("container", () -> {
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException x) {
                throw new RuntimeException(x);
            }
        });
        p.setDefinition(new CpsFlowDefinition("node('remote') {writeFile file: 'f', text: '.'; archiveArtifacts 'f'; timeout(time: 3, unit: 'SECONDS') {unarchive mapping: ['f': 'f']}}", true));
        j.assertLogContains(new TimeoutStepExecution.ExceededTimeout(null).getShortDescription(), j.assertBuildStatus(Result.ABORTED, p.scheduleBuild2(0)));
    }

    @Test
    void errorCleaningArtifacts() throws Exception {
        logger.record(WorkflowRun.class, Level.WARNING).record("jenkins.model.BackgroundGlobalBuildDiscarder", Level.WARNING).record(GlobalBuildDiscarderListener.class, Level.WARNING).capture(10);
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("node('remote') {writeFile file: 'f', text: '.'; archiveArtifacts 'f'}", true));
        j.buildAndAssertSuccess(p);
        p.setBuildDiscarder(new LogRotator(-1, -1, -1, 0));
        MockApiMetadata.handleRemoveBlob("container", "p/1/artifacts/f", () -> {throw new ContainerNotFoundException("container", "sorry about your artifacts");});
        j.buildAndAssertSuccess(p);
        expectLogMessage("container not found: sorry about your artifacts");
    }

    @Test
    void errorCleaningStashes() throws Exception {
        logger.record(WorkflowRun.class, Level.WARNING).record("jenkins.model.BackgroundGlobalBuildDiscarder", Level.WARNING).capture(10);
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("node('remote') {writeFile file: 'f', text: '.'; stash 'stuff'}", true));
        MockApiMetadata.handleRemoveBlob("container", "p/1/stashes/stuff.tgz", () -> {throw new ContainerNotFoundException("container", "sorry about your stashes");});
        WorkflowRun b = j.buildAndAssertSuccess(p);
        if (!JenkinsRule.getLog(b).contains("container not found: sorry about your stashes")) {
            // TODO delete after https://github.com/jenkinsci/workflow-job-plugin/pull/357
            expectLogMessage("container not found: sorry about your stashes");
        }
    }

    // Interrupts probably never delivered during HTTP requests (maybe depends on servlet container?).
    // Hangs would be handled by jclouds code.
    @Test
    void errorBrowsing() throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("node('remote') {writeFile file: 'f', text: '.'; archiveArtifacts 'f'}", true));
        WorkflowRun b = j.buildAndAssertSuccess(p);
        MockApiMetadata.handleGetBlobKeysInsideContainer("container", () -> {throw new ContainerNotFoundException("container", "sorry");});
        JenkinsRule.WebClient wc = j.createWebClient();
        {
            System.err.println("build root");
            logger.record(Run.class, Level.WARNING).capture(10);
            wc.getPage(b);
            expectLogMessage("container not found: sorry");
        }
        {
            System.err.println("artifact root");
            MockApiMetadata.handleGetBlobKeysInsideContainer("container", () -> {throw new ContainerNotFoundException("container", "really sorry");});
            try {
                logger.record(InstallUncaughtExceptionHandler.class, Level.WARNING).capture(10);
                wc.getPage(b, "artifact/");
                fail("Currently DirectoryBrowserSupport throws up storage exceptions.");
            } catch (FailingHttpStatusCodeException x) {
                assertEquals(500, x.getStatusCode());
                String responseText = x.getResponse().getContentAsString();
                String expectedError = "container not found: really sorry";
                if (!responseText.contains(expectedError)) { // Jenkins 2.224+
                    expectLogMessage(expectedError);
                }
            }
        }
    }

    private void expectLogMessage(String message) throws InterruptedException {
        while (logger.getRecords().stream().map(LogRecord::getThrown).filter(Objects::nonNull).map(Functions::printThrowable).noneMatch(t -> t.contains(message))) {
            Thread.sleep(100);
        }
    }

    private static void failIn(BlobStoreProvider.HttpMethod method, String key, int code, int repeats) {
        MockBlobStore.speciallyHandle(method, key, (request, response, context) -> {
            if (repeats > 0) {
                failIn(method, key, code, repeats - 1);
            }
            if (code == 0) {
                throw new ConnectionClosedException("Refusing to even send a status code for " + key);
            }
            response.setStatusLine(new BasicStatusLine(HttpVersion.HTTP_1_0, code, "simulated " + code + " failure"));
            response.setEntity(new StringEntity("Detailed explanation of " + code + "."));
        });
    }

    private static void hangIn(BlobStoreProvider.HttpMethod method, String key) {
        MockBlobStore.speciallyHandle(method, key, (request, response, context) -> {
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException x) {
                fail(x); // on the server side, should not happen
            }
        });
    }
}
