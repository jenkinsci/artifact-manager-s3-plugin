package io.jenkins.plugins.artifact_manager_jclouds.s3;

import static org.junit.Assert.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.logging.Logger;

import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.AnonymousAWSCredentials;
import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.cloudbees.jenkins.plugins.awscredentials.BaseAmazonWebServicesCredentials;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.domains.Domain;

import hudson.model.Failure;
import hudson.util.FormValidation;
import io.findify.s3mock.S3Mock;
import io.jenkins.plugins.artifact_manager_jclouds.BlobStoreProvider;
import io.jenkins.plugins.artifact_manager_jclouds.JCloudsArtifactManagerFactory;
import io.jenkins.plugins.aws.global_configuration.CredentialsAwsGlobalConfiguration;
import jenkins.model.ArtifactManagerConfiguration;

public class S3BlobStoreConfigTest {

    private static final Logger LOGGER = Logger.getLogger(S3BlobStoreConfigTest.class.getName());

    public static final String CONTAINER_NAME = "container-name";
    public static final String CONTAINER_PREFIX = "container-prefix/";
    public static final String CONTAINER_REGION = "us-west-1";
    public static final Boolean CONTAINER_ACCELERATED_ENDPOINT = false;

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void checkConfigurationManually() throws Exception {
        S3BlobStore provider = new S3BlobStore();
        S3BlobStoreConfig config = S3BlobStoreConfig.get();
        config.setContainer(CONTAINER_NAME);
        config.setPrefix(CONTAINER_PREFIX);
        config.setAcceleratedEndpoint(CONTAINER_ACCELERATED_ENDPOINT);

        JCloudsArtifactManagerFactory artifactManagerFactory = new JCloudsArtifactManagerFactory(provider);
        ArtifactManagerConfiguration.get().getArtifactManagerFactories().add(artifactManagerFactory);

        LOGGER.info(artifactManagerFactory.getProvider().toString());
        BlobStoreProvider providerConfigured = artifactManagerFactory.getProvider();
        assertTrue(providerConfigured instanceof S3BlobStore);
        checkFieldValues(config);

        //check configuration page submit
        j.configRoundtrip();
        checkFieldValues(config);
    }

    private void checkFieldValues(S3BlobStoreConfig configuration) {
        assertEquals(configuration.getContainer(), CONTAINER_NAME);
        assertEquals(configuration.getPrefix(), CONTAINER_PREFIX);
        assertEquals(configuration.getAcceleratedEndpoint(), CONTAINER_ACCELERATED_ENDPOINT);
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
    public void createS3Bucket() throws IOException {
        int port =  findFreePort();
        String serviceEndpoint = "http://127.0.0.1:" + port;
        S3BlobStoreConfig.ENDPOINT = new EndpointConfiguration(serviceEndpoint, CONTAINER_REGION);
        S3BlobStoreConfig config = S3BlobStoreConfig.get();
        config.setContainer(CONTAINER_NAME);
        config.setPrefix(CONTAINER_PREFIX);
        config.setAcceleratedEndpoint(CONTAINER_ACCELERATED_ENDPOINT);
        CredentialsAwsGlobalConfiguration credentialsConfig = CredentialsAwsGlobalConfiguration.get();
        credentialsConfig.setRegion(CONTAINER_REGION);
        CredentialsProvider.lookupStores(j.jenkins).iterator().next().addCredentials(Domain.global(), new PhonySessionCredentials(CredentialsScope.GLOBAL, "phony", null));
        credentialsConfig.setCredentialsId("phony");

        S3Mock api = new S3Mock.Builder().withPort(port).withInMemoryBackend().build();
        api.start();

        config.createS3Bucket(CONTAINER_NAME);

        AwsClientBuilder.EndpointConfiguration endpoint = new AwsClientBuilder.EndpointConfiguration(serviceEndpoint, CONTAINER_REGION);
        AmazonS3 client = AmazonS3ClientBuilder
                .standard()
                .withPathStyleAccessEnabled(true)
                .withEndpointConfiguration(endpoint)
                .withCredentials(new AWSStaticCredentialsProvider(new AnonymousAWSCredentials()))
                .build();
        client.putObject(CONTAINER_NAME, "file/name", "contents");
        api.shutdown();
    }
    private static final class PhonySessionCredentials extends BaseAmazonWebServicesCredentials {
        PhonySessionCredentials(CredentialsScope scope, String id, String description) {
            super(scope, id, description);
        }
        @Override
        public AWSCredentials getCredentials() {
            return new BasicSessionCredentials("FakeKey", "FakeSecret", "FakeToken");
        }
        @Override
        public String getDisplayName() {
            return "Phony";
        }
        @Override
        public AWSCredentials getCredentials(String mfaToken) {
            return getCredentials();
        }
        @Override
        public void refresh() {}
    }

    private Integer findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
