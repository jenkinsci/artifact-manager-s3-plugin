package io.jenkins.plugins.artifact_manager_jclouds.s3;

import java.util.logging.Logger;

import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import io.jenkins.plugins.artifact_manager_jclouds.BlobStoreProvider;
import io.jenkins.plugins.artifact_manager_jclouds.JCloudsArtifactManagerFactory;

import hudson.model.Failure;
import hudson.util.FormValidation;
import jenkins.model.ArtifactManagerConfiguration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class S3BlobStoreConfigTest {

    private static final Logger LOGGER = Logger.getLogger(S3BlobStoreConfigTest.class.getName());

    public static final String CONTAINER_NAME = "container-name";
    public static final String CONTAINER_PREFIX = "container-prefix/";
    public static final String CONTAINER_REGION = "us-west-1";
    public static final String CUSTOM_ENDPOINT = "internal-s3.company.org:9000";
    public static final String CUSTOM_ENDPOINT_SIGNING_REGION = "us-west-2";
    public static final boolean USE_PATH_STYLE = true;
    public static final boolean USE_HTTP = true;
    public static final boolean DISABLE_SESSION_TOKEN = true;

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void checkConfigurationManually() throws Exception {
        S3BlobStore provider = new S3BlobStore();
        S3BlobStoreConfig config = S3BlobStoreConfig.get();
        config.setContainer(CONTAINER_NAME);
        config.setPrefix(CONTAINER_PREFIX);
        config.setCustomEndpoint(CUSTOM_ENDPOINT);
        config.setCustomSigningRegion(CUSTOM_ENDPOINT_SIGNING_REGION);
        config.setUsePathStyleUrl(USE_PATH_STYLE);
        config.setUseHttp(USE_HTTP);
        config.setDisableSessionToken(DISABLE_SESSION_TOKEN);

        JCloudsArtifactManagerFactory artifactManagerFactory = new JCloudsArtifactManagerFactory(provider);
        ArtifactManagerConfiguration.get().getArtifactManagerFactories().add(artifactManagerFactory);

        LOGGER.info(artifactManagerFactory.getProvider().toString());
        BlobStoreProvider providerConfigured = artifactManagerFactory.getProvider();
        assertThat(providerConfigured, instanceOf(S3BlobStore.class));
        checkFieldValues(config);

        //check configuration page submit
        j.configRoundtrip();
        checkFieldValues(config);
    }

    private void checkFieldValues(S3BlobStoreConfig configuration) {
        assertEquals(CONTAINER_NAME, configuration.getContainer());
        assertEquals(CONTAINER_PREFIX, configuration.getPrefix());
        assertEquals(CUSTOM_ENDPOINT, S3BlobStoreConfig.get().getCustomEndpoint());
        assertEquals(CUSTOM_ENDPOINT_SIGNING_REGION, S3BlobStoreConfig.get().getCustomSigningRegion());
        assertEquals(USE_PATH_STYLE, S3BlobStoreConfig.get().getUsePathStyleUrl());
        assertEquals(USE_HTTP, S3BlobStoreConfig.get().getUseHttp());
        assertEquals(DISABLE_SESSION_TOKEN, S3BlobStoreConfig.get().getDisableSessionToken());
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
    public void checkValidationCustomEndPoint() {
        S3BlobStoreConfig descriptor = S3BlobStoreConfig.get();
        assertEquals(descriptor.doCheckCustomEndpoint("").kind, FormValidation.Kind.OK);
        assertEquals(descriptor.doCheckCustomEndpoint("server").kind, FormValidation.Kind.OK);
        assertEquals(descriptor.doCheckCustomEndpoint("server.organisation.tld").kind, FormValidation.Kind.OK);
        assertEquals(descriptor.doCheckCustomEndpoint("server:8080").kind, FormValidation.Kind.OK);
        assertEquals(descriptor.doCheckCustomEndpoint("server.organisation.tld:8080").kind, FormValidation.Kind.OK);
        assertEquals(descriptor.doCheckCustomEndpoint("s3-server.organisation.tld").kind, FormValidation.Kind.OK);
        assertEquals(descriptor.doCheckCustomEndpoint("-server.organisation.tld").kind, FormValidation.Kind.ERROR);
        assertEquals(descriptor.doCheckCustomEndpoint(".server.organisation.tld").kind, FormValidation.Kind.ERROR);
    }

    @Test
    public void checkValidationCustomSigningRegion() {
        S3BlobStoreConfig descriptor = S3BlobStoreConfig.get();
        assertEquals(descriptor.doCheckCustomSigningRegion("anystring").kind, FormValidation.Kind.OK);
        assertEquals(descriptor.doCheckCustomSigningRegion("").kind, FormValidation.Kind.OK);
        descriptor.setCustomEndpoint("server");
        assertTrue(descriptor.doCheckCustomSigningRegion("").getMessage().contains("us-east-1"));
    }

}
