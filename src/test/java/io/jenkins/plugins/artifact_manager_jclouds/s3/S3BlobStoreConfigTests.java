package io.jenkins.plugins.artifact_manager_jclouds.s3;

import java.io.IOException;
import java.util.logging.Logger;
import io.jenkins.plugins.artifact_manager_jclouds.BlobStoreProvider;
import io.jenkins.plugins.artifact_manager_jclouds.JCloudsArtifactManagerFactory;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import hudson.model.Failure;
import hudson.util.FormValidation;
import jenkins.model.ArtifactManagerConfiguration;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class S3BlobStoreConfigTests {

    private static final Logger LOGGER = Logger.getLogger(S3BlobStoreConfigTests.class.getName());

    public static final String CONTAINER_NAME = "container-name";
    public static final String CONTAINER_PREFIX = "container-prefix/";
    public static final String CONTAINER_REGION = "us-west-1";

    @Rule
    public JenkinsRule j = new JenkinsRule();

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
}
