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

import hudson.model.Result;
import jenkins.model.ArtifactManagerConfiguration;
import org.apache.http.ConnectionClosedException;
import org.apache.http.HttpVersion;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicStatusLine;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.TimeoutStepExecution;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;

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
public class NetworkTest {

    @ClassRule
    public static BuildWatcher buildWatcher = new BuildWatcher();

    @Rule
    public JenkinsRule r = new JenkinsRule();

    @Before
    public void configureManager() throws Exception {
        MockBlobStore mockBlobStore = new MockBlobStore();
        mockBlobStore.getContext().getBlobStore().createContainerInLocation(null, mockBlobStore.getContainer());
        ArtifactManagerConfiguration.get().getArtifactManagerFactories().add(new JCloudsArtifactManagerFactory(mockBlobStore));
    }

    @Test
    public void unrecoverableErrorArchiving() throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        r.createSlave("remote", null, null);
        failIn(BlobStoreProvider.HttpMethod.PUT, "p/1/artifacts/f", 403, 0);
        p.setDefinition(new CpsFlowDefinition("node('remote') {writeFile file: 'f', text: '.'; archiveArtifacts 'f'}", true));
        WorkflowRun b = r.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0));
        r.assertLogContains("ERROR: Failed to upload", b);
        r.assertLogContains("/container/p/1/artifacts/f?…, response: 403 simulated 403 failure, body: Detailed explanation of 403.", b);
        r.assertLogNotContains("Retrying upload", b);
        r.assertLogNotContains("\tat hudson.tasks.ArtifactArchiver.perform", b);
    }

    @Test
    public void recoverableErrorArchiving() throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        r.createSlave("remote", null, null);
        failIn(BlobStoreProvider.HttpMethod.PUT, "p/1/artifacts/f", 500, 0);
        p.setDefinition(new CpsFlowDefinition("node('remote') {writeFile file: 'f', text: '.'; archiveArtifacts 'f'}", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
        r.assertLogContains("/container/p/1/artifacts/f?…, response: 500 simulated 500 failure, body: Detailed explanation of 500.", b);
        r.assertLogContains("Retrying upload", b);
        r.assertLogNotContains("\tat hudson.tasks.ArtifactArchiver.perform", b);
    }

    @Test
    public void networkExceptionArchiving() throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        r.createSlave("remote", null, null);
        failIn(BlobStoreProvider.HttpMethod.PUT, "p/1/artifacts/f", 0, 0);
        p.setDefinition(new CpsFlowDefinition("node('remote') {writeFile file: 'f', text: '.'; archiveArtifacts 'f'}", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
        // currently prints a ‘java.net.SocketException: Connection reset’ but not sure if we really care
        r.assertLogContains("Retrying upload", b);
        r.assertLogNotContains("\tat hudson.tasks.ArtifactArchiver.perform", b);
    }

    @Test
    public void repeatedRecoverableErrorArchiving() throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        r.createSlave("remote", null, null);
        int origStopAfterAttemptNumber = JCloudsArtifactManager.STOP_AFTER_ATTEMPT_NUMBER;
        JCloudsArtifactManager.STOP_AFTER_ATTEMPT_NUMBER = 3;
        try {
            failIn(BlobStoreProvider.HttpMethod.PUT, "p/1/artifacts/f", 500, JCloudsArtifactManager.STOP_AFTER_ATTEMPT_NUMBER);
            p.setDefinition(new CpsFlowDefinition("node('remote') {writeFile file: 'f', text: '.'; archiveArtifacts 'f'}", true));
            WorkflowRun b = r.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0));
            r.assertLogContains("ERROR: Failed to upload", b);
            r.assertLogContains("/container/p/1/artifacts/f?…, response: 500 simulated 500 failure, body: Detailed explanation of 500.", b);
            r.assertLogContains("Retrying upload", b);
            r.assertLogNotContains("\tat hudson.tasks.ArtifactArchiver.perform", b);
        } finally {
            JCloudsArtifactManager.STOP_AFTER_ATTEMPT_NUMBER = origStopAfterAttemptNumber;
        }
    }

    @Test
    public void hangArchiving() throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        r.createSlave("remote", null, null);
        long origTimeout = JCloudsArtifactManager.TIMEOUT;
        JCloudsArtifactManager.TIMEOUT = 5;
        try {
            hangIn(BlobStoreProvider.HttpMethod.PUT, "p/1/artifacts/f");
            p.setDefinition(new CpsFlowDefinition("node('remote') {writeFile file: 'f', text: '.'; archiveArtifacts 'f'}", true));
            WorkflowRun b = r.buildAndAssertSuccess(p);
            r.assertLogContains("Retrying upload", b);
            r.assertLogNotContains("\tat hudson.tasks.ArtifactArchiver.perform", b);
            // Also from master:
            hangIn(BlobStoreProvider.HttpMethod.PUT, "p/2/artifacts/f");
            p.setDefinition(new CpsFlowDefinition("node('master') {writeFile file: 'f', text: '.'; archiveArtifacts 'f'}", true));
            b = r.buildAndAssertSuccess(p);
            r.assertLogContains("Retrying upload", b);
            r.assertLogNotContains("\tat hudson.tasks.ArtifactArchiver.perform", b);
        } finally {
            JCloudsArtifactManager.TIMEOUT = origTimeout;
        }
    }

    @Test
    public void interruptedArchiving() throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        r.createSlave("remote", null, null);
        hangIn(BlobStoreProvider.HttpMethod.PUT, "p/1/artifacts/f");
        p.setDefinition(new CpsFlowDefinition("node('remote') {writeFile file: 'f', text: '.'; archiveArtifacts 'f'}", true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        r.waitForMessage("Archiving artifacts", b);
        Thread.sleep(2000); // wait for hangIn to sleep; OK if occasionally it has not gotten there yet, we still expect the same result
        b.getExecutor().interrupt();
        r.assertBuildStatus(Result.ABORTED, r.waitForCompletion(b));
        // Currently prints a stack trace of java.lang.InterruptedException; good enough.
        // Check the same from a timeout within the build, rather than a user abort, and also from master just for fun:
        hangIn(BlobStoreProvider.HttpMethod.PUT, "p/2/artifacts/f");
        p.setDefinition(new CpsFlowDefinition("node('master') {writeFile file: 'f', text: '.'; timeout(time: 3, unit: 'SECONDS') {archiveArtifacts 'f'}}", true));
        r.assertLogContains(new TimeoutStepExecution.ExceededTimeout().getShortDescription(), r.assertBuildStatus(Result.ABORTED, p.scheduleBuild2(0)));
    }

    @Test
    public void unrecoverableErrorUnstashing() throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        r.createSlave("remote", null, null);
        failIn(BlobStoreProvider.HttpMethod.GET, "p/1/stashes/f.tgz", 403, 0);
        p.setDefinition(new CpsFlowDefinition("node('remote') {writeFile file: 'f', text: '.'; stash 'f'; unstash 'f'}", true));
        WorkflowRun b = r.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0));
        r.assertLogContains("ERROR: Failed to download", b);
        r.assertLogContains("/container/p/1/stashes/f.tgz?…", b);
        r.assertLogContains("response: 403 simulated 403 failure, body: Detailed explanation of 403.", b);
        r.assertLogNotContains("Retrying download", b);
        r.assertLogNotContains("\tat org.jenkinsci.plugins.workflow.flow.StashManager.unstash", b);
    }

    @Test
    public void recoverableErrorUnstashing() throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        r.createSlave("remote", null, null);
        failIn(BlobStoreProvider.HttpMethod.GET, "p/1/stashes/f.tgz", 500, 0);
        p.setDefinition(new CpsFlowDefinition("node('remote') {writeFile file: 'f', text: '.'; stash 'f'; unstash 'f'}", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
        r.assertLogContains("/container/p/1/stashes/f.tgz?…", b);
        r.assertLogContains("response: 500 simulated 500 failure, body: Detailed explanation of 500.", b);
        r.assertLogContains("Retrying download", b);
        r.assertLogNotContains("\tat org.jenkinsci.plugins.workflow.flow.StashManager.unstash", b);
    }

    @Ignore("TODO failing to simulate an error using failIn(…, 0, …); maybe need to set a custom Entity that throws an exception later?")
    @Test
    public void networkExceptionUnstashing() throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        r.createSlave("remote", null, null);
        failIn(BlobStoreProvider.HttpMethod.GET, "p/1/stashes/f.tgz", 0, 0);
        p.setDefinition(new CpsFlowDefinition("node('remote') {writeFile file: 'f', text: '.'; stash 'f'; unstash 'f'}", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
        r.assertLogContains("Retrying download", b);
        r.assertLogNotContains("\tat org.jenkinsci.plugins.workflow.flow.StashManager.unstash", b);
    }

    @Test
    public void repeatedRecoverableErrorUnstashing() throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        r.createSlave("remote", null, null);
        int origStopAfterAttemptNumber = JCloudsArtifactManager.STOP_AFTER_ATTEMPT_NUMBER;
        JCloudsArtifactManager.STOP_AFTER_ATTEMPT_NUMBER = 3;
        try {
            failIn(BlobStoreProvider.HttpMethod.GET, "p/1/stashes/f.tgz", 500, JCloudsArtifactManager.STOP_AFTER_ATTEMPT_NUMBER);
            p.setDefinition(new CpsFlowDefinition("node('remote') {writeFile file: 'f', text: '.'; stash 'f'; unstash 'f'}", true));
            WorkflowRun b = r.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0));
            r.assertLogContains("ERROR: Failed to download", b);
            r.assertLogContains("/container/p/1/stashes/f.tgz?…", b);
            r.assertLogContains("response: 500 simulated 500 failure, body: Detailed explanation of 500.", b);
            r.assertLogContains("Retrying download", b);
            r.assertLogNotContains("\tat org.jenkinsci.plugins.workflow.flow.StashManager.unstash", b);
        } finally {
            JCloudsArtifactManager.STOP_AFTER_ATTEMPT_NUMBER = origStopAfterAttemptNumber;
        }
    }

    @Test
    public void hangUnstashing() throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        r.createSlave("remote", null, null);
        long origTimeout = JCloudsArtifactManager.TIMEOUT;
        JCloudsArtifactManager.TIMEOUT = 5;
        try {
            hangIn(BlobStoreProvider.HttpMethod.GET, "p/1/stashes/f.tgz");
            p.setDefinition(new CpsFlowDefinition("node('remote') {writeFile file: 'f', text: '.'; stash 'f'; unstash 'f'}", true));
            WorkflowRun b = r.buildAndAssertSuccess(p);
            r.assertLogContains("Retrying download", b);
            r.assertLogNotContains("\tat org.jenkinsci.plugins.workflow.flow.StashManager.unstash", b);
            hangIn(BlobStoreProvider.HttpMethod.GET, "p/2/stashes/f.tgz");
            p.setDefinition(new CpsFlowDefinition("node('master') {writeFile file: 'f', text: '.'; stash 'f'; unstash 'f'}", true));
            b = r.buildAndAssertSuccess(p);
            r.assertLogContains("Retrying download", b);
            r.assertLogNotContains("\tat org.jenkinsci.plugins.workflow.flow.StashManager.unstash", b);
        } finally {
            JCloudsArtifactManager.TIMEOUT = origTimeout;
        }
    }

    @Test
    public void interruptedUnstashing() throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        r.createSlave("remote", null, null);
        hangIn(BlobStoreProvider.HttpMethod.GET, "p/1/stashes/f.tgz");
        p.setDefinition(new CpsFlowDefinition("node('remote') {writeFile file: 'f', text: '.'; stash 'f'; unstash 'f'}", true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        r.waitForMessage("[Pipeline] unstash", b);
        Thread.sleep(2000);
        b.getExecutor().interrupt();
        r.assertBuildStatus(Result.ABORTED, r.waitForCompletion(b));
        hangIn(BlobStoreProvider.HttpMethod.GET, "p/2/stashes/f.tgz");
        p.setDefinition(new CpsFlowDefinition("node('master') {writeFile file: 'f', text: '.'; stash 'f'; timeout(time: 3, unit: 'SECONDS') {unstash 'f'}}", true));
        r.assertLogContains(new TimeoutStepExecution.ExceededTimeout().getShortDescription(), r.assertBuildStatus(Result.ABORTED, p.scheduleBuild2(0)));
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
                assert false : x; // on the server side, should not happen
            }
        });
    }

}
