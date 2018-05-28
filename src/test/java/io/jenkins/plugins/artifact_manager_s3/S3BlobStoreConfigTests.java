package io.jenkins.plugins.artifact_manager_s3;

import java.util.logging.Logger;
import io.jenkins.plugins.artifact_manager_jclouds.JCloudsArtifactManagerFactory;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import hudson.util.FormValidation;
import jenkins.model.ArtifactManagerConfiguration;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class S3BlobStoreConfigTests {

    protected static final Logger LOGGER = Logger.getLogger(JCloudsVirtualFileTest.class.getName());
    public static final String CONTAINER_NAME = "container-name";
    public static final String CONTAINER_PREFIX = "container-prefix";
    public static final String CONTAINER_REGION = "us-west-1";

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void checkConfigurationFromProperties() {
        System.setProperty(S3BlobStore.class.getName() + ".container", CONTAINER_NAME);
        System.setProperty(S3BlobStore.class.getName() + ".prefix", CONTAINER_PREFIX);
        System.setProperty(S3BlobStore.class.getName() + ".region", CONTAINER_REGION);

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
        provider.setContainer(CONTAINER_NAME);
        provider.setPrefix(CONTAINER_PREFIX);
        provider.setRegion(CONTAINER_REGION);

        JCloudsArtifactManagerFactory artifactManagerFactory = new JCloudsArtifactManagerFactory(provider);
        ArtifactManagerConfiguration.get().getArtifactManagerFactories().add(artifactManagerFactory);

        LOGGER.info(artifactManagerFactory.getProvider().toString());

        assertEquals(artifactManagerFactory.getProvider().getContainer(), CONTAINER_NAME);
        assertEquals(artifactManagerFactory.getProvider().getPrefix(), CONTAINER_PREFIX);
        assertTrue(artifactManagerFactory.getProvider() instanceof S3BlobStore);
        assertEquals(((S3BlobStore)artifactManagerFactory.getProvider()).getRegion(), CONTAINER_REGION);
        LOGGER.info(artifactManagerFactory.getProvider().toString());
    }

    @Test
    public void checkValidationsContainer() {
        S3BlobStore.DescriptorImpl descriptor = new S3BlobStore.DescriptorImpl();
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
    }

    @Test
    public void checkValidationsPrefix() {
        S3BlobStore.DescriptorImpl descriptor = new S3BlobStore.DescriptorImpl();
        assertEquals(descriptor.doCheckPrefix("").kind, FormValidation.Kind.ERROR);
        assertEquals(descriptor.doCheckPrefix("folder").kind, FormValidation.Kind.WARNING);
        assertEquals(descriptor.doCheckPrefix("folder/").kind, FormValidation.Kind.OK);
    }

    @Test
    public void checkValidationsRegion() {
        S3BlobStore.DescriptorImpl descriptor = new S3BlobStore.DescriptorImpl();
        assertEquals(descriptor.doCheckRegion("").kind, FormValidation.Kind.OK);
        assertEquals(descriptor.doCheckRegion(null).kind, FormValidation.Kind.OK);
        assertEquals(descriptor.doCheckRegion("us-west-1").kind, FormValidation.Kind.OK);
        assertEquals(descriptor.doCheckRegion("no-valid").kind, FormValidation.Kind.ERROR);
    }
}
