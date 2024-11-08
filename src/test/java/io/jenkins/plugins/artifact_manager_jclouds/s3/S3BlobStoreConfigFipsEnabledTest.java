package io.jenkins.plugins.artifact_manager_jclouds.s3;

import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.RealJenkinsRule;
import hudson.util.FormValidation;
import static org.junit.Assert.assertEquals;

import java.io.IOException;


public class S3BlobStoreConfigFipsEnabledTest {

    @Rule
    public RealJenkinsRule rule = new RealJenkinsRule().omitPlugins("eddsa-api").javaOptions("-Djenkins.security.FIPS140.COMPLIANCE=true");


    @Test
    public void checkUseHttpsWithFipsEnabledTest() throws Throwable {
        rule.then(S3BlobStoreConfigFipsEnabledTest::checkUseHttpsWithFipsEnabled);
    }


    private static void checkUseHttpsWithFipsEnabled(JenkinsRule r) throws IOException {
        S3BlobStoreConfig descriptor = S3BlobStoreConfig.get();
        assertEquals(descriptor.doCheckUseHttp(true).kind , FormValidation.Kind.ERROR);
        assertEquals(descriptor.doCheckUseHttp(false).kind , FormValidation.Kind.OK);
    }
}
