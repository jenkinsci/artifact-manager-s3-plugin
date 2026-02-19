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

import jenkins.model.ArtifactManagerConfiguration;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.domain.Blob;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.JenkinsRule;
import org.testcontainers.DockerClientFactory;

import java.net.URL;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeFalse;

public class DisablePresignedUrlsTest {

    @ClassRule
    public static BuildWatcher buildWatcher = new BuildWatcher();

    @Rule
    public JenkinsRule r = new JenkinsRule();

    private MockBlobStore mockBlobStore;

    @Before
    public void configureManager() throws Exception {
        mockBlobStore = new MockBlobStore();
        mockBlobStore.getContext().getBlobStore().createContainerInLocation(null, mockBlobStore.getContainer());
        ArtifactManagerConfiguration.get().getArtifactManagerFactories().add(new JCloudsArtifactManagerFactory(mockBlobStore));
    }

    @Test
    public void defaultProviderDoesNotDisablePresignedUrls() {
        MockBlobStore provider = new MockBlobStore();
        assertFalse(provider.isDisablePresignedUrls());
    }

    @Test
    public void toExternalURLReturnsUrlWhenPresignedUrlsEnabled() throws Exception {
        mockBlobStore.setDisablePresignedUrls(false);

        BlobStore blobStore = mockBlobStore.getContext().getBlobStore();
        Blob blob = blobStore.blobBuilder("test-key").payload("test content").build();
        blobStore.putBlob(mockBlobStore.getContainer(), blob);

        JCloudsVirtualFile vf = new JCloudsVirtualFile(mockBlobStore, mockBlobStore.getContainer(), "test-key");
        URL url = vf.toExternalURL();
        assertNotNull("toExternalURL should return a URL when presigned URLs are enabled", url);
    }

    @Test
    public void toExternalURLReturnsNullWhenPresignedUrlsDisabled() throws Exception {
        mockBlobStore.setDisablePresignedUrls(true);

        BlobStore blobStore = mockBlobStore.getContext().getBlobStore();
        Blob blob = blobStore.blobBuilder("test-key").payload("test content").build();
        blobStore.putBlob(mockBlobStore.getContainer(), blob);

        JCloudsVirtualFile vf = new JCloudsVirtualFile(mockBlobStore, mockBlobStore.getContainer(), "test-key");
        URL url = vf.toExternalURL();
        assertNull("toExternalURL should return null when presigned URLs are disabled", url);
    }

    @Test
    public void artifactsServedThroughControllerWhenPresignedUrlsDisabled() throws Exception {
        assumeFalse("Does not work when Dockerized since the mock server is inaccessible from the container",
                DockerClientFactory.instance().isDockerAvailable());
        mockBlobStore.setDisablePresignedUrls(true);
        r.createSlave("remote", null, null);

        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "node('remote') {writeFile file: 'f', text: 'hello'; archiveArtifacts 'f'}", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);

        // Artifact should be accessible via Jenkins controller (not redirected to S3)
        JenkinsRule.WebClient wc = r.createWebClient();
        String artifactContent = wc.goTo(b.getUrl() + "artifact/f", "application/octet-stream").getWebResponse().getContentAsString();
        assertEquals("hello", artifactContent);
    }

    @Test
    public void artifactsStillWorkWithPresignedUrlsEnabled() throws Exception {
        assumeFalse("Does not work when Dockerized since the mock server is inaccessible from the container",
                DockerClientFactory.instance().isDockerAvailable());
        mockBlobStore.setDisablePresignedUrls(false);
        r.createSlave("remote", null, null);

        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "node('remote') {writeFile file: 'f', text: 'hello'; archiveArtifacts 'f'}", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);

        JCloudsVirtualFile root = (JCloudsVirtualFile) b.getArtifactManager().root();
        JCloudsVirtualFile artifact = (JCloudsVirtualFile) root.child("f");
        URL externalUrl = artifact.toExternalURL();
        assertNotNull("toExternalURL should return a presigned URL when not disabled", externalUrl);
    }

    @Test
    public void stashAndUnstashStillWorkWithPresignedUrlsDisabled() throws Exception {
        assumeFalse("Does not work when Dockerized since the mock server is inaccessible from the container",
                DockerClientFactory.instance().isDockerAvailable());
        mockBlobStore.setDisablePresignedUrls(true);
        r.createSlave("remote", null, null);

        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "node('remote') {writeFile file: 'f', text: 'stashed'; stash 'mystash'; unstash 'mystash'}", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
        r.assertLogContains("Stashed 1 file(s)", b);
        r.assertLogContains("Unstashed file(s)", b);
    }
}
