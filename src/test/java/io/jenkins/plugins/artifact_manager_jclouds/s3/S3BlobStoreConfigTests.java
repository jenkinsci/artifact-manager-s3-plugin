package io.jenkins.plugins.artifact_manager_jclouds.s3;

import java.util.logging.Logger;
import io.jenkins.plugins.artifact_manager_jclouds.BlobStoreProvider;
import io.jenkins.plugins.artifact_manager_jclouds.JCloudsArtifactManagerFactory;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import jenkins.model.ArtifactManagerConfiguration;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
}
