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

package io.jenkins.plugins.artifact_manager_jclouds.s3;

import io.jenkins.plugins.artifact_manager_jclouds.BlobStoreProviderDescriptor;
import io.jenkins.plugins.artifact_manager_jclouds.BlobStoreProvider;
import io.jenkins.plugins.artifact_manager_jclouds.JCloudsArtifactManagerFactory;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.logging.Level;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.NullOutputStream;
import org.jclouds.rest.internal.InvokeHttpMethod;
import org.jenkinsci.plugins.workflow.ArtifactManagerTest;
import org.jenkinsci.test.acceptance.docker.DockerImage;
import org.jenkinsci.test.acceptance.docker.fixtures.JavaContainer;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LoggerRule;
import org.jvnet.hudson.test.TestBuilder;

import com.amazonaws.SdkClientException;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;

import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.remoting.Which;
import hudson.slaves.DumbSlave;
import hudson.tasks.ArtifactArchiver;
import java.net.URI;
import java.net.URL;
import java.util.Collections;
import jenkins.branch.BranchSource;
import jenkins.model.ArtifactManagerConfiguration;
import jenkins.model.ArtifactManagerFactory;
import jenkins.model.Jenkins;
import jenkins.plugins.git.GitSCMSource;
import jenkins.plugins.git.GitSampleRepoRule;
import jenkins.plugins.git.traits.BranchDiscoveryTrait;
import jenkins.security.MasterToSlaveCallable;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jvnet.hudson.test.Issue;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.domain.Blob;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProjectTest;
import org.junit.Ignore;

public class JCloudsArtifactManagerTest extends S3AbstractTest {

    @ClassRule
    public static BuildWatcher buildWatcher = new BuildWatcher();

    @BeforeClass
    public static void live() {
        S3AbstractTest.live();
    }

    private static DockerImage image;

    @BeforeClass
    public static void doPrepareImage() throws Exception {
        image = ArtifactManagerTest.prepareImage();
    }

    @Rule
    public LoggerRule httpLogging = new LoggerRule();

    @Rule
    public GitSampleRepoRule sampleRepo = new GitSampleRepoRule();

    protected ArtifactManagerFactory getArtifactManagerFactory(Boolean deleteArtifacts, Boolean deleteStashes) {
        return new JCloudsArtifactManagerFactory(new CustomPrefixBlobStoreProvider(provider, getPrefix(), deleteArtifacts, deleteStashes, getAcceleratedEndpoint()));
    }

    private static final class CustomPrefixBlobStoreProvider extends BlobStoreProvider {
        private final BlobStoreProvider delegate;
        private final String prefix;
        private final Boolean deleteArtifacts, deleteStashes;
        private final Boolean acceleratedEndpoint;
        CustomPrefixBlobStoreProvider(BlobStoreProvider delegate, String prefix, Boolean deleteArtifacts, Boolean deleteStashes, Boolean acceleratedEndpoint) {
            this.delegate = delegate;
            this.prefix = prefix;
            this.deleteArtifacts = deleteArtifacts;
            this.deleteStashes = deleteStashes;
            this.acceleratedEndpoint = acceleratedEndpoint;
        }
        @Override
        public String getPrefix() {
            return prefix;
        }
        @Override
        public String getContainer() {
            return delegate.getContainer();
        }
        @Override
        public boolean isDeleteArtifacts() {
            return deleteArtifacts != null ? deleteArtifacts : delegate.isDeleteArtifacts();
        }
        @Override
        public boolean isDeleteStashes() {
            return deleteStashes != null ? deleteStashes : delegate.isDeleteStashes();
        }
        @Override
        public Boolean getAcceleratedEndpoint() {
            return acceleratedEndpoint;
        }
        @Override
        public BlobStoreContext getContext() throws IOException {
            return delegate.getContext();
        }
        @Override
        public URI toURI(String container, String key) {
            return delegate.toURI(container, key);
        }
        @Override
        public URL toExternalURL(Blob blob, HttpMethod httpMethod) throws IOException {
            return delegate.toExternalURL(blob, httpMethod);
        }
        @Override
        public BlobStoreProviderDescriptor getDescriptor() {
            return delegate.getDescriptor();
        }
    }

    @Test
    public void agentPermissions() throws Exception {
        assumeNotNull(image);
        System.err.println("verifying that while the master can connect to S3, a Dockerized agent cannot");
        try (JavaContainer container = image.start(JavaContainer.class).start()) {
            DumbSlave agent = new DumbSlave("assumptions", "/home/test/slave", new SSHLauncher(container.ipBound(22), container.port(22), "test", "test", "", ""));
            Jenkins.get().addNode(agent);
            j.waitOnline(agent);
            try {
                agent.getChannel().call(new LoadS3Credentials());
                fail("did not expect to be able to connect to S3 from a Dockerized agent"); // or AssumptionViolatedException?
            } catch (SdkClientException x) {
                System.err.println("a Dockerized agent was unable to connect to S3, as expected: " + x);
            }
        }
    }

    @Test
    public void artifactArchive() throws Exception {
        // To demo class loading performance: loggerRule.record(SlaveComputer.class, Level.FINEST);
        ArtifactManagerTest.artifactArchive(j, getArtifactManagerFactory(null, null), /* TODO S3BlobStore.list does not seem to handle weird characters */false, image);
    }

    @Test
    public void artifactArchiveAndDelete() throws Exception {
        ArtifactManagerTest.artifactArchiveAndDelete(j, getArtifactManagerFactory(true, null), false, image);
    }

    @Test
    public void artifactStash() throws Exception {
        ArtifactManagerTest.artifactStash(j, getArtifactManagerFactory(null, null), false, image);
    }

    @Test
    public void artifactStashAndDelete() throws Exception {
        ArtifactManagerTest.artifactStashAndDelete(j, getArtifactManagerFactory(null, true), false, image);
    }

    private static final class LoadS3Credentials extends MasterToSlaveCallable<Void, RuntimeException> {
        @Override
        public Void call() {
            AmazonS3ClientBuilder.standard().build();
            return null;
        }
    }

    @Test
    public void artifactBrowsingPerformance() throws Exception {
        ArtifactManagerConfiguration.get().getArtifactManagerFactories().add(getArtifactManagerFactory(null, null));
        FreeStyleProject p = j.createFreeStyleProject();
        p.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
                FilePath ws = build.getWorkspace();
                for (int i = 0; i < 10; i++) {
                    for (int j = 0; j < 10; j++) {
                        ws.child(i + "/" + j + "/f").write(i + "-" + j, null);
                    }
                }
                return true;
            }
        });
        p.getPublishersList().add(new ArtifactArchiver("**"));
        FreeStyleBuild b = j.buildAndAssertSuccess(p);
        httpLogging.record(InvokeHttpMethod.class, Level.FINE);
        httpLogging.capture(1000);
        JenkinsRule.WebClient wc = j.createWebClient();
        // Exercise DirectoryBrowserSupport & Run.getArtifactsUpTo
        System.err.println("build root");
        wc.getPage(b);
        System.err.println("artifact root");
        wc.getPage(b, "artifact/");
        System.err.println("3 subdir");
        wc.getPage(b, "artifact/3/");
        System.err.println("3/4 subdir");
        wc.getPage(b, "artifact/3/4/");
        int httpCount = httpLogging.getRecords().size();
        System.err.println("total count: " + httpCount);
        assertThat(httpCount, lessThanOrEqualTo(11));
    }

    @Issue({"JENKINS-51390", "JCLOUDS-1200"})
    @Test
    public void serializationProblem() throws Exception {
        ArtifactManagerConfiguration.get().getArtifactManagerFactories().add(getArtifactManagerFactory(null, null));
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("node {writeFile file: 'f', text: 'content'; archiveArtifacts 'f'; dir('d') {try {unarchive mapping: ['f': 'f']} catch (x) {sleep 1; echo(/caught $x/)}}}", true));
        S3BlobStore.BREAK_CREDS = true;
        try {
            WorkflowRun b = j.buildAndAssertSuccess(p);
            j.assertLogContains("caught java.io.IOException: org.jclouds.aws.AWSResponseException", b);
            j.assertLogNotContains("java.io.NotSerializableException", b);
        } finally {
            S3BlobStore.BREAK_CREDS = false;
        }
    }

    @Ignore("TODO fails in unarchive, apparently due to JCLOUDS-1401")
    @Issue("JENKINS-52151")
    @Test
    public void slashyBranches() throws Exception {
        ArtifactManagerConfiguration.get().getArtifactManagerFactories().add(getArtifactManagerFactory(true, true));
        sampleRepo.init();
        sampleRepo.git("checkout", "-b", "dev/main");
        sampleRepo.write("Jenkinsfile", "node {dir('src') {writeFile file: 'f', text: 'content'; archiveArtifacts 'f'; stash 'x'}; dir('dest') {unstash 'x'; unarchive mapping: ['f': 'f2']}}");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("commit", "--message=flow");
        WorkflowMultiBranchProject mp = j.jenkins.createProject(WorkflowMultiBranchProject.class, "p");
        GitSCMSource gitSCMSource = new GitSCMSource(sampleRepo.toString());
        gitSCMSource.setTraits(Collections.singletonList(new BranchDiscoveryTrait()));
        mp.getSourcesList().add(new BranchSource(gitSCMSource));
        WorkflowJob p = WorkflowMultiBranchProjectTest.scheduleAndFindBranchProject(mp, "dev%2Fmain");
        assertEquals(1, mp.getItems().size());
        j.waitUntilNoActivity();
        WorkflowRun b = p.getLastBuild();
        assertEquals(1, b.getNumber());
        j.assertBuildStatusSuccess(b);
        URL url = b.getArtifactManager().root().child("f").toExternalURL();
        System.out.println("Defined: " + url);
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getPage(b);
        wc.getPage(b, "artifact/");
        assertEquals("content", wc.goTo(b.getUrl() + "artifact/f", null).getWebResponse().getContentAsString());
        b.deleteArtifacts();
    }

    //@Test
    public void archiveSingleLargeFile() throws Exception {
        ArtifactManagerConfiguration.get().getArtifactManagerFactories().add(getArtifactManagerFactory(null, null));
        FreeStyleProject p = j.createFreeStyleProject();
        p.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                    throws InterruptedException, IOException {
                FilePath target = build.getWorkspace().child("out");
                long length = 2L * 1024 * 1024 * 1024;
                final FilePath src = new FilePath(Which.jarFile(Jenkins.class));

                final OutputStream out = target.write();
                try {
                    do {
                        IOUtils.copy(src.read(), out);
                    } while (target.length() < length);
                } finally {
                    out.close();
                }
                return true;
            }
        });
        p.getPublishersList().add(new ArtifactArchiver("**/*"));
        FreeStyleBuild build = j.buildAndAssertSuccess(p);
        InputStream out = build.getArtifactManager().root().child("out").open();
        try {
            IOUtils.copy(out, new NullOutputStream());
        } finally {
            out.close();
        }
    }

}
