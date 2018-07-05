package io.jenkins.plugins.artifact_manager_jclouds.s3;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.AnonymousAWSCredentials;
import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import io.jenkins.plugins.artifact_manager_jclouds.BlobStoreProvider;
import io.jenkins.plugins.artifact_manager_jclouds.JCloudsArtifactManagerFactory;
import org.jclouds.rest.internal.InvokeHttpMethod;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Failure;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.tasks.ArtifactArchiver;
import hudson.util.FormValidation;
import jenkins.model.ArtifactManagerConfiguration;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;


import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;
import io.findify.s3mock.S3Mock;
import org.jvnet.hudson.test.LoggerRule;
import org.jvnet.hudson.test.TestBuilder;

public class S3BlobStoreConfigTests {

    private static final Logger LOGGER = Logger.getLogger(S3BlobStoreConfigTests.class.getName());

    public static final String CONTAINER_NAME = "container-name";
    public static final String CONTAINER_PREFIX = "container-prefix/";
    public static final String CONTAINER_REGION = "us-west-1";

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Rule
    public LoggerRule httpLogging = new LoggerRule();

    @Test
    public void checkConfigurationManually() throws Exception {
        S3BlobStore provider = new S3BlobStore();
        S3BlobStoreConfig s3BlobStoreConfig = S3BlobStoreConfig.get();
        s3BlobStoreConfig.setContainer(CONTAINER_NAME);
        s3BlobStoreConfig.setPrefix(CONTAINER_PREFIX);
        s3BlobStoreConfig.setRegion(CONTAINER_REGION);

        JCloudsArtifactManagerFactory artifactManagerFactory = new JCloudsArtifactManagerFactory(provider);
        ArtifactManagerConfiguration.get().getArtifactManagerFactories().add(artifactManagerFactory);

        LOGGER.info(artifactManagerFactory.getProvider().toString());
        BlobStoreProvider providerConfigured = artifactManagerFactory.getProvider();
        assertTrue(providerConfigured instanceof S3BlobStore);
        checkFieldValues(((S3BlobStore)providerConfigured).getConfiguration());

        //check configuration page submit
        j.configRoundtrip();
        checkFieldValues(S3BlobStoreConfig.get());
    }

    private void checkFieldValues(S3BlobStoreConfig configuration) {
        assertEquals(configuration.getContainer(), CONTAINER_NAME);
        assertEquals(configuration.getPrefix(), CONTAINER_PREFIX);
        assertEquals(configuration.getRegion(), CONTAINER_REGION);
    }

    @Test(expected = Failure.class)
    public void checkContainerWrongConfiguration() {
        S3BlobStoreConfig descriptor = S3BlobStoreConfig.get();
        descriptor.setContainer("/wrong-container-name");
        fail();
    }

    @Test
    public void checkValidationsContainer() {
        S3BlobStoreConfig descriptor = S3BlobStoreConfig.get();
        assertEquals(descriptor.doCheckContainer("aaa").kind, FormValidation.Kind.OK);
        assertEquals(descriptor.doCheckContainer("aaa12345678901234567890123456789012345678901234568901234567890")
                             .kind, FormValidation.Kind.OK);
        assertEquals(descriptor.doCheckContainer("name.1name.name1").kind, FormValidation.Kind.OK);
        assertEquals(descriptor.doCheckContainer("name-1name-name1").kind, FormValidation.Kind.OK);

        assertEquals(descriptor.doCheckContainer("AAA").kind, FormValidation.Kind.ERROR);
        assertEquals(descriptor.doCheckContainer("A_A").kind, FormValidation.Kind.ERROR);
        assertEquals(descriptor.doCheckContainer("Name").kind, FormValidation.Kind.ERROR);
        assertEquals(descriptor.doCheckContainer("-name").kind, FormValidation.Kind.ERROR);
        assertEquals(descriptor.doCheckContainer(".name").kind, FormValidation.Kind.ERROR);
        assertEquals(descriptor.doCheckContainer("_name").kind, FormValidation.Kind.ERROR);
        assertEquals(descriptor.doCheckContainer("192.168.1.100").kind, FormValidation.Kind.ERROR);
        assertEquals(descriptor.doCheckContainer("name-Name").kind, FormValidation.Kind.ERROR);
        assertEquals(descriptor.doCheckContainer("name-namE").kind, FormValidation.Kind.ERROR);
        assertEquals(descriptor.doCheckContainer("name_").kind, FormValidation.Kind.ERROR);
        assertEquals(descriptor.doCheckContainer("/name").kind, FormValidation.Kind.ERROR);
    }

    @Test
    public void checkValidationsPrefix() {
        S3BlobStoreConfig descriptor = S3BlobStoreConfig.get();
        assertEquals(descriptor.doCheckPrefix("").kind, FormValidation.Kind.OK);
        assertEquals(descriptor.doCheckPrefix("folder/").kind, FormValidation.Kind.OK);
        assertEquals(descriptor.doCheckPrefix("folder").kind, FormValidation.Kind.ERROR);
    }

    @Test
    public void checkValidationsRegion() {
        S3BlobStoreConfig descriptor = S3BlobStoreConfig.get();
        assertEquals(descriptor.doCheckRegion("").kind, FormValidation.Kind.OK);
        assertEquals(descriptor.doCheckRegion("us-west-1").kind, FormValidation.Kind.OK);
        assertEquals(descriptor.doCheckRegion("no-valid").kind, FormValidation.Kind.ERROR);
    }

    @Test
    public void createS3Bucket() throws IOException {
        int port =  findFreePort();
        String serviceEndpoint = "http://127.0.0.1:" + port;
        S3BlobStoreConfig.ENDPOINT = new EndpointConfiguration(serviceEndpoint, CONTAINER_REGION);
        S3BlobStore provider = new S3BlobStore();
        S3BlobStoreConfig s3BlobStoreConfig = S3BlobStoreConfig.get();
        s3BlobStoreConfig.setContainer(CONTAINER_NAME);
        s3BlobStoreConfig.setPrefix(CONTAINER_PREFIX);
        s3BlobStoreConfig.setRegion(CONTAINER_REGION);

        S3Mock api = new S3Mock.Builder().withPort(port).withInMemoryBackend().build();
        api.start();

        provider.createS3Bucket(CONTAINER_NAME);

        AwsClientBuilder.EndpointConfiguration endpoint = new AwsClientBuilder.EndpointConfiguration(serviceEndpoint, CONTAINER_REGION);
        AmazonS3 client = AmazonS3ClientBuilder
                .standard()
                .withPathStyleAccessEnabled(true)
                .withEndpointConfiguration(endpoint)
                .withCredentials(new AWSStaticCredentialsProvider(new AnonymousAWSCredentials()))
                .build();
        client.putObject(CONTAINER_NAME, "file/name", "contents");
        api.stop();
    }

    @Test
    public void archiveArtifactMockFilesystem() throws Exception {
        FileBlobStore provider = new FileBlobStore();

        JCloudsArtifactManagerFactory artifactManagerFactory = new JCloudsArtifactManagerFactory(provider);
        ArtifactManagerConfiguration.get().getArtifactManagerFactories().add(artifactManagerFactory);

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

    private Integer findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
