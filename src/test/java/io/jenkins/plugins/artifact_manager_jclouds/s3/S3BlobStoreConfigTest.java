package io.jenkins.plugins.artifact_manager_jclouds.s3;

import java.util.logging.Logger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.jvnet.hudson.test.JenkinsRule;
import io.jenkins.plugins.artifact_manager_jclouds.BlobStoreProvider;
import io.jenkins.plugins.artifact_manager_jclouds.JCloudsArtifactManagerFactory;

import hudson.model.Failure;
import hudson.util.FormValidation;
import jenkins.model.ArtifactManagerConfiguration;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.*;

@WithJenkins
class S3BlobStoreConfigTest {

    private static final Logger LOGGER = Logger.getLogger(S3BlobStoreConfigTest.class.getName());

    public static final String CONTAINER_NAME = "container-name";
    public static final String CONTAINER_PREFIX = "container-prefix/";
    public static final String CONTAINER_REGION = "us-west-1";
    public static final String CUSTOM_ENDPOINT = "internal-s3.company.org:9000";
    public static final String CUSTOM_ENDPOINT_SIGNING_REGION = "us-west-2";
    public static final boolean USE_PATH_STYLE = true;
    public static final boolean USE_HTTP = true;
    public static final boolean DISABLE_SESSION_TOKEN = true;

    private JenkinsRule j;

    @BeforeEach
    void beforeEach(JenkinsRule rule) {
        j = rule;
    }

    @Test
    void checkConfigurationManually() throws Exception {
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

    @Test
    void checkContainerWrongConfiguration() {
        S3BlobStoreConfig descriptor = S3BlobStoreConfig.get();
        assertThrows(Failure.class, () -> descriptor.setContainer("/wrong-container-name"));
    }

    @Test
    void checkValidationsContainer() {
        S3BlobStoreConfig descriptor = S3BlobStoreConfig.get();
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckContainer("aaa").kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckContainer("aaa12345678901234567890123456789012345678901234568901234567890")
                .kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckContainer("name.1name.name1").kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckContainer("name-1name-name1").kind);

        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckContainer("AAA").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckContainer("A_A").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckContainer("Name").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckContainer("-name").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckContainer(".name").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckContainer("_name").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckContainer("192.168.1.100").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckContainer("name-Name").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckContainer("name-namE").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckContainer("name_").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckContainer("/name").kind);
    }

    @Test
    void checkValidationsPrefix() {
        S3BlobStoreConfig descriptor = S3BlobStoreConfig.get();
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckPrefix("").kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckPrefix("folder/").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckPrefix("folder").kind);
    }

    @Test
    void checkValidationCustomEndPoint() {
        S3BlobStoreConfig descriptor = S3BlobStoreConfig.get();
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckCustomEndpoint("").kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckCustomEndpoint("server").kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckCustomEndpoint("server.organisation.tld").kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckCustomEndpoint("server:8080").kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckCustomEndpoint("server.organisation.tld:8080").kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckCustomEndpoint("s3-server.organisation.tld").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckCustomEndpoint("-server.organisation.tld").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckCustomEndpoint(".server.organisation.tld").kind);
    }

    @Test
    void checkValidationCustomSigningRegion() {
        S3BlobStoreConfig descriptor = S3BlobStoreConfig.get();
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckCustomSigningRegion("anystring").kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckCustomSigningRegion("").kind);
        descriptor.setCustomEndpoint("server");
        assertTrue(descriptor.doCheckCustomSigningRegion("").getMessage().contains("us-east-1"));
    }

    @Test
    void checkValidationUseHttpsWithFipsDisabled() {
        S3BlobStoreConfig descriptor = S3BlobStoreConfig.get();
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckUseHttp(true).kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckUseHttp(false).kind);
    }
}
