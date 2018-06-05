package io.jenkins.plugins.artifact_manager_s3;

import java.util.logging.Logger;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.gargoylesoftware.htmlunit.html.HtmlSelect;
import com.gargoylesoftware.htmlunit.html.HtmlTextInput;
import io.jenkins.plugins.artifact_manager_jclouds.JCloudsArtifactManagerFactory;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.RestartableJenkinsRule;
import jenkins.model.ArtifactManagerConfiguration;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class S3BlobStoreConfigTests {

    private static final Logger LOGGER = Logger.getLogger(JCloudsVirtualFileTest.class.getName());

    public static final String CONTAINER_NAME = "container-name";
    public static final String CONTAINER_PREFIX = "container-prefix";
    public static final String CONTAINER_REGION = "us-west-1";

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Rule
    public RestartableJenkinsRule rr = new RestartableJenkinsRule();

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

        checkFieldValues(artifactManagerFactory);

        //check configuration page submit
        j.configRoundtrip();
        checkFieldValues(artifactManagerFactory);
    }

    private void checkFieldValues(JCloudsArtifactManagerFactory artifactManagerFactory) {
        assertEquals(artifactManagerFactory.getProvider().getContainer(), CONTAINER_NAME);
        assertEquals(artifactManagerFactory.getProvider().getPrefix(), CONTAINER_PREFIX);
        assertTrue(artifactManagerFactory.getProvider() instanceof S3BlobStore);
        assertEquals(((S3BlobStore)artifactManagerFactory.getProvider()).getRegion(), CONTAINER_REGION);
    }
}
