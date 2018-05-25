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
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.Before;
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
 *     The jclouds {@code AWSS3ProviderMetadata} cannot accept a custom {@link AmazonS3} which {@code S3MockRule} would supply;
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
    public void exceptionArchiving() throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        r.createSlave("remote", null, null);
        MockBlobStore.failIn(BlobStoreProvider.HttpMethod.PUT, "p/1/artifacts/f");
        p.setDefinition(new CpsFlowDefinition("node('remote') {writeFile file: 'f', text: '.'; archiveArtifacts 'f'}", true));
        WorkflowRun b = r.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0));
        r.assertLogContains("/container/p/1/artifacts/f?…, response: 500 simulated failure, body: Detailed explanation.", b);
    }

}
