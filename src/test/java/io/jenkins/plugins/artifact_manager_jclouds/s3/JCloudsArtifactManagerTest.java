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

import com.amazonaws.SdkClientException;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.gargoylesoftware.htmlunit.WebResponse;

import hudson.ExtensionList;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.*;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.remoting.Which;
import hudson.slaves.DumbSlave;
import hudson.tasks.ArtifactArchiver;
import io.jenkins.plugins.artifact_manager_jclouds.BlobStoreProvider;
import io.jenkins.plugins.artifact_manager_jclouds.BlobStoreProviderDescriptor;
import io.jenkins.plugins.artifact_manager_jclouds.JCloudsArtifactManagerFactory;
import io.jenkins.plugins.aws.global_configuration.CredentialsAwsGlobalConfiguration;
import jenkins.branch.BranchSource;
import jenkins.model.ArtifactManagerConfiguration;
import jenkins.model.ArtifactManagerFactory;
import jenkins.model.Jenkins;
import jenkins.plugins.git.GitSCMSource;
import jenkins.plugins.git.GitSampleRepoRule;
import jenkins.plugins.git.traits.BranchDiscoveryTrait;
import jenkins.security.MasterToSlaveCallable;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.NullOutputStream;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.rest.internal.InvokeHttpMethod;
import org.jenkinsci.plugins.workflow.ArtifactManagerTest;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jvnet.hudson.test.Issue;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.domain.Blob;
import org.jenkinsci.plugins.workflow.flow.FlowCopier;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProjectTest;
import org.jenkinsci.test.acceptance.docker.DockerImage;
import org.jenkinsci.test.acceptance.docker.fixtures.JavaContainer;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.util.Collections;
import java.util.logging.Level;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeNotNull;

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
        return new JCloudsArtifactManagerFactory(new CustomBehaviorBlobStoreProvider(provider, deleteArtifacts, deleteStashes));
    }

    @Test
    public void agentPermissions() throws Exception {
        assumeNotNull(image);
        System.err.println("verifying that while the master can connect to S3, a Dockerized agent cannot");
        try (JavaContainer container = image.start(JavaContainer.class).start()) {
            SystemCredentialsProvider.getInstance().getDomainCredentialsMap().put(Domain.global(), Collections.singletonList(new UsernamePasswordCredentialsImpl(CredentialsScope.SYSTEM, "test", null, "test", "test")));
            DumbSlave agent = new DumbSlave("assumptions", "/home/test/slave", new SSHLauncher(container.ipBound(22), container.port(22), "test"));
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
        ArtifactManagerTest.artifactArchive(j, getArtifactManagerFactory(null, null), true, image);
    }

    @Test
    public void artifactArchiveAndDelete() throws Exception {
        ArtifactManagerTest.artifactArchiveAndDelete(j, getArtifactManagerFactory(true, null), true, image);
    }

    @Test
    public void artifactStash() throws Exception {
        ArtifactManagerTest.artifactStash(j, getArtifactManagerFactory(null, null), true, image);
    }

    @Test
    public void artifactStashAndDelete() throws Exception {
        ArtifactManagerTest.artifactStashAndDelete(j, getArtifactManagerFactory(null, true), true, image);
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
        assertThat(httpCount, lessThanOrEqualTo(13));
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

    @Issue({"JENKINS-52151", "JENKINS-60040"})
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
        sampleRepo.write("Jenkinsfile", "");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("commit", "--message=empty");
        WorkflowRun b2 = j.buildAndAssertSuccess(p);
        for (FlowCopier copier : ExtensionList.lookup(FlowCopier.class)) {
            copier.copy(b.asFlowExecutionOwner(), b2.asFlowExecutionOwner());
        }
        assertTrue(b2.getArtifactManager().root().child("f").isFile());
        b.deleteArtifacts();
    }

    @Issue("JENKINS-56004")
    @Test
    public void nonAdmin() throws Exception {
        CredentialsAwsGlobalConfiguration.get().setCredentialsId("bogus"); // force sessionCredentials to call getCredentials
        ArtifactManagerConfiguration.get().getArtifactManagerFactories().add(getArtifactManagerFactory(null, null));
        Folder d = j.createProject(Folder.class, "d");
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy().
                grant(Jenkins.ADMINISTER).everywhere().to("admin").
                grant(Jenkins.READ).everywhere().to("dev1", "dev2").
                grant(Item.READ).onFolders(d).to("dev2"));
        WorkflowJob p = d.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("node {writeFile file: 'f.txt', text: ''; archiveArtifacts 'f.txt'}", true));
        WorkflowRun b = j.buildAndAssertSuccess(p);
        String url = "job/d/job/p/1/api/json?tree=artifacts[relativePath]";
        String jsonType = "application/json";
        String snippet = "\"relativePath\":\"f.txt\"";
        assertThat(j.createWebClient().withBasicCredentials("admin").goTo(url, jsonType).getWebResponse().getContentAsString(), containsString(snippet));
        j.createWebClient().withBasicCredentials("dev1").assertFails(url, 404);
        assertThat(j.createWebClient().withBasicCredentials("dev2").goTo(url, jsonType).getWebResponse().getContentAsString(), containsString(snippet));
    }

    @Issue("JENKINS-50772")
    @Test
    public void contentType() throws Exception {
        String text = "some regular text";
        String html = "<html><header></header><body>Test file contents</body></html>";

        ArtifactManagerConfiguration.get().getArtifactManagerFactories().add(getArtifactManagerFactory(null, null));

        j.createSlave("remote", null, null);

        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("node('remote') {writeFile file: 'f.txt', text: '" + text + "'; writeFile file: 'f.html', text: '" + html + "'; writeFile file: 'f', text: '\\u0000'; archiveArtifacts 'f*'}", true));
        j.buildAndAssertSuccess(p);

        WebResponse response = j.createWebClient().goTo("job/p/1/artifact/f.txt", null).getWebResponse();
        assertThat(response.getContentAsString(), equalTo(text));
        assertThat(response.getContentType(), equalTo("text/plain"));
        response = j.createWebClient().goTo("job/p/1/artifact/f.html", null).getWebResponse();
        assertThat(response.getContentAsString(), equalTo(html));
        assertThat(response.getContentType(), equalTo("text/html"));
        response = j.createWebClient().goTo("job/p/1/artifact/f", null).getWebResponse();
        assertThat(response.getContentLength(), equalTo(1L));
        assertThat(response.getContentType(), containsString("/octet-stream"));
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
