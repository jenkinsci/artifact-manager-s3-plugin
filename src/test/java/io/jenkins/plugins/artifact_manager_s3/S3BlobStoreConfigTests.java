package io.jenkins.plugins.artifact_manager_s3;

import java.util.logging.Logger;
import io.jenkins.plugins.artifact_manager_jclouds.JCloudsArtifactManagerFactory;
import org.apache.commons.lang.StringUtils;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import jenkins.model.ArtifactManagerConfiguration;
import static io.jenkins.plugins.artifact_manager_s3.S3BlobStore.KEY_CONTAINER;
import static io.jenkins.plugins.artifact_manager_s3.S3BlobStore.KEY_PREFIX;
import static io.jenkins.plugins.artifact_manager_s3.S3BlobStore.KEY_REGION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class S3BlobStoreConfigTests {

    private static final Logger LOGGER = Logger.getLogger(JCloudsVirtualFileTest.class.getName());

    public static final String CONTAINER_NAME = "container-name";
    public static final String CONTAINER_PREFIX = "container-prefix";
    public static final String CONTAINER_REGION = "us-west-1";

    public static final String ORG_CONTAINER = System.getProperty(KEY_CONTAINER);
    public static final String ORG_PREFIX = System.getProperty(KEY_PREFIX);
    public static final String ORG_REGION = System.getProperty(KEY_REGION);
    public static final String MANUAL = "-Manual";

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @After
    public void cleanup(){
        if (StringUtils.isBlank(ORG_CONTAINER)){
            System.clearProperty(KEY_CONTAINER);
        } else {
            System.setProperty(KEY_CONTAINER, ORG_CONTAINER);
        }

        if (StringUtils.isBlank(ORG_PREFIX)){
            System.clearProperty(KEY_PREFIX);
        } else {
            System.setProperty(KEY_PREFIX, ORG_PREFIX);
        }

        if (StringUtils.isBlank(ORG_REGION)){
            System.clearProperty(KEY_REGION);
        } else {
            System.setProperty(KEY_REGION, ORG_PREFIX);
        }
    }

    @Test
    public void checkConfigurationFromProperties() {
        System.setProperty(KEY_CONTAINER, CONTAINER_NAME);
        System.setProperty(KEY_PREFIX, CONTAINER_PREFIX);
        System.setProperty(KEY_REGION, CONTAINER_REGION);

        S3BlobStore provider = new S3BlobStore();
        JCloudsArtifactManagerFactory artifactManagerFactory = new JCloudsArtifactManagerFactory(provider);
        ArtifactManagerConfiguration.get().getArtifactManagerFactories().add(artifactManagerFactory);

        LOGGER.info(artifactManagerFactory.getProvider().toString());

        assertEquals(artifactManagerFactory.getProvider().getContainer(), CONTAINER_NAME);
        assertEquals(artifactManagerFactory.getProvider().getPrefix(), CONTAINER_PREFIX);
        assertTrue(artifactManagerFactory.getProvider() instanceof S3BlobStore);
        assertEquals(((S3BlobStore)artifactManagerFactory.getProvider()).getRegion(), CONTAINER_REGION);
    }

    @Test
    public void checkConfigurationManually() {
        S3BlobStore provider = new S3BlobStore();
        provider.setContainer(CONTAINER_NAME + MANUAL);
        provider.setPrefix(CONTAINER_PREFIX + MANUAL);
        provider.setRegion(CONTAINER_REGION + MANUAL);

        JCloudsArtifactManagerFactory artifactManagerFactory = new JCloudsArtifactManagerFactory(provider);
        ArtifactManagerConfiguration.get().getArtifactManagerFactories().add(artifactManagerFactory);

        LOGGER.info(artifactManagerFactory.getProvider().toString());

        assertEquals(artifactManagerFactory.getProvider().getContainer(), CONTAINER_NAME + MANUAL);
        assertEquals(artifactManagerFactory.getProvider().getPrefix(), CONTAINER_PREFIX + MANUAL);
        assertTrue(artifactManagerFactory.getProvider() instanceof S3BlobStore);
        assertEquals(((S3BlobStore)artifactManagerFactory.getProvider()).getRegion(), CONTAINER_REGION + MANUAL);
        LOGGER.info(artifactManagerFactory.getProvider().toString());
    }
}
