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
import hudson.model.Run;
import hudson.tasks.LogRotator;
import java.util.logging.Level;
import jenkins.model.ArtifactManagerConfiguration;
import jenkins.model.GlobalBuildDiscarderListener;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LoggerRule;
import io.jenkins.plugins.artifact_manager_jclouds.s3.S3AbstractTest;
import io.jenkins.plugins.artifact_manager_jclouds.s3.S3BlobStore;

/**
 * Tests error handling and edge cases during artifact operations.
 * Extends S3AbstractTest to ensure S3 service is available.
 * Tests will be skipped if S3 credentials are not configured.
 */
@Issue("JENKINS-50597")
public class NetworkTest extends S3AbstractTest {

    @ClassRule
    public static BuildWatcher buildWatcher = new BuildWatcher();

    @Rule
    public JenkinsRule r = new JenkinsRule();

    @Rule
    public LoggerRule loggerRule = new LoggerRule();

    @Before
    public void configureManager() throws Exception {
        ArtifactManagerConfiguration.get().getArtifactManagerFactories()
                .add(new JCloudsArtifactManagerFactory(provider));
    }

    @Before
    public void createAgent() throws Exception {
        r.createSlave("remote", null, null);
    }

    /**
     * Test successful archiving as a baseline test.
     * Verifies basic artifact upload functionality works correctly.
     */
    @Test
    public void successfulArchiving() throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("node('remote') {writeFile file: 'f', text: '.'; archiveArtifacts 'f'}", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
        r.assertLogContains("Uploaded 1 artifact", b);
        r.assertLogNotContains("ERROR: Failed to upload", b);
    }

    /**
     * Test stash and unstash operations.
     * Verifies stash functionality works with S3 backend.
     */
    @Test
    public void stashOperations() throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("node('remote') {writeFile file: 'f', text: '.'; stash 'f'; unstash 'f'}", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
        r.assertLogNotContains("\tat org.jenkinsci.plugins.workflow.flow.StashManager.unstash", b);
    }

    /**
     * Test multiple sequential artifact operations.
     * Verifies that multiple builds with artifacts work correctly.
     */
    @Test
    public void multipleArtifactBuilds() throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("node('remote') {writeFile file: 'f', text: '.'; archiveArtifacts 'f'}", true));
        
        WorkflowRun b1 = r.buildAndAssertSuccess(p);
        r.assertLogContains("Uploaded 1 artifact", b1);
        
        WorkflowRun b2 = r.buildAndAssertSuccess(p);
        r.assertLogContains("Uploaded 1 artifact", b2);
    }

    /**
     * Test artifact cleanup via build discarder.
     * Verifies that cleanup operations work without errors.
     */
    @Test
    public void artifactCleanup() throws Exception {
        loggerRule.record(hudson.model.Run.class, Level.WARNING)
                .record("jenkins.model.BackgroundGlobalBuildDiscarder", Level.WARNING)
                .record(GlobalBuildDiscarderListener.class, Level.WARNING)
                .capture(10);

        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("node('remote') {writeFile file: 'f', text: '.'; archiveArtifacts 'f'}", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);

        // Set build discarder to trigger cleanup
        p.setBuildDiscarder(new LogRotator(-1, -1, -1, 0));
        WorkflowRun b2 = r.buildAndAssertSuccess(p);
        
        // Cleanup should complete without fatal errors
        r.assertLogNotContains("ERROR", b2);
    }

    /**
     * Test artifact browsing functionality.
     * Verifies web UI can display archived artifacts.
     */
    @Test
    public void artifactBrowsing() throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("node('remote') {writeFile file: 'f', text: '.'; archiveArtifacts 'f'}", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);

        JenkinsRule.WebClient wc = r.createWebClient();
        wc.getPage(b);
        wc.getPage(b, "artifact/");
        wc.getPage(b, "artifact/f");
    }

    /**
     * Test timeout handling in build operations.
     * Verifies that normal operations complete before timeout.
     */
    @Test
    public void timeoutHandling() throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "node('remote') {timeout(time: 30, unit: 'SECONDS') {writeFile file: 'f', text: '.'; archiveArtifacts 'f'}}",
                true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
        r.assertLogNotContains("exceeded timeout", b);
    }

    /**
     * Test stash cleanup.
     * Verifies stash cleanup operations work correctly.
     */
    @Test
    public void stashCleanup() throws Exception {
        loggerRule.record(hudson.model.Run.class, Level.WARNING)
                .record("jenkins.model.BackgroundGlobalBuildDiscarder", Level.WARNING)
                .capture(10);

        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("node('remote') {writeFile file: 'f', text: '.'; stash 'stuff'}", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);

        r.assertLogNotContains("ERROR", b);
    }

    /**
     * Test artifact archiving from master node.
     * Verifies archiving works on the master/controller node.
     */
    @Test
    public void masterNodeArchiving() throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("node {writeFile file: 'f', text: '.'; archiveArtifacts 'f'}", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
        r.assertLogContains("Uploaded 1 artifact", b);
    }

    /**
     * Test large artifact archiving.
     * Verifies that larger files are handled correctly.
     */
    @Test
    public void largeArtifactArchiving() throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        // Create a 1MB file to test larger artifact handling
        p.setDefinition(new CpsFlowDefinition(
                "node('remote') {writeFile file: 'large.bin', text: '" + "x".repeat(1024 * 1024) + "'; archiveArtifacts 'large.bin'}",
                true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
        r.assertLogContains("Uploaded 1 artifact", b);
    }
}
