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

package io.jenkins.plugins.artifact_manager_s3;

import com.amazonaws.SdkClientException;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.logging.Level;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.NullOutputStream;
import org.jclouds.rest.internal.InvokeHttpMethod;
import org.jenkinsci.plugins.workflow.ArtifactManagerTest;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LoggerRule;
import org.jvnet.hudson.test.TestBuilder;

import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Run;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.remoting.Which;
import hudson.slaves.DumbSlave;
import hudson.tasks.ArtifactArchiver;
import jenkins.model.ArtifactManager;
import jenkins.model.ArtifactManagerConfiguration;
import jenkins.model.ArtifactManagerFactory;
import jenkins.model.Jenkins;
import jenkins.security.MasterToSlaveCallable;
import org.jenkinsci.test.acceptance.docker.DockerImage;
import org.jenkinsci.test.acceptance.docker.fixtures.JavaContainer;
import org.junit.ClassRule;
import org.jvnet.hudson.test.BuildWatcher;

public class JCloudsArtifactManagerTest {

    @ClassRule
    public static BuildWatcher buildWatcher = new BuildWatcher();

    @BeforeClass
    public static void live() {
        JCloudsAbstractTest.live();
    }

    private static DockerImage image;

    @BeforeClass
    public static void doPrepareImage() throws Exception {
        image = ArtifactManagerTest.prepareImage();
    }

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Rule
    public LoggerRule loggerRule = new LoggerRule().recordPackage(JCloudsBlobStore.class, Level.ALL);
    @Rule
    public LoggerRule httpLogging = new LoggerRule();

    private String uuid;

    private static class ArtifactManagerFactoryForTesting extends JCloudsArtifactManagerFactory {
        private String uuid;

        public ArtifactManagerFactoryForTesting(String uuid) {
            this.uuid = uuid;
        }

        @Override
        public ArtifactManager managerFor(Run<?, ?> build) {
            // use a different dir for each test
            JCloudsArtifactManager manager = (JCloudsArtifactManager) super.managerFor(build);
            manager.setPrefix(String.format("%s%s/", manager.getPrefix(), uuid));
            return manager;
        }
    }

    @Before
    public void generateUUID() {
        this.uuid = UUID.randomUUID().toString();
    }

    protected ArtifactManagerFactory getArtifactManagerFactory() {
        return new ArtifactManagerFactoryForTesting(uuid);
    }

    @Test
    public void smokes() throws Exception {
        if (image != null) {
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
        // To demo class loading performance: loggerRule.record(SlaveComputer.class, Level.FINEST);
        ArtifactManagerTest.run(j, getArtifactManagerFactory(), /* TODO S3BlobStore.list does not seem to handle weird characters */false, image);
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
        ArtifactManagerConfiguration.get().getArtifactManagerFactories().add(getArtifactManagerFactory());
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

    //@Test
    public void archiveSingleLargeFile() throws Exception {
        ArtifactManagerConfiguration.get().getArtifactManagerFactories().add(getArtifactManagerFactory());
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
