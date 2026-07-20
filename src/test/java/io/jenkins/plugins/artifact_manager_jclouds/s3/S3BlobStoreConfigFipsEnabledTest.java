package io.jenkins.plugins.artifact_manager_jclouds.s3;

import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import hudson.util.FormValidation;
import org.jvnet.hudson.test.junit.jupiter.RealJenkinsExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;


class S3BlobStoreConfigFipsEnabledTest {

    @RegisterExtension
    private final RealJenkinsExtension extension = new RealJenkinsExtension().omitPlugins("eddsa-api").javaOptions("-Djenkins.security.FIPS140.COMPLIANCE=true");


    @Test
    void checkUseHttpsWithFipsEnabledTest() throws Throwable {
        extension.then(S3BlobStoreConfigFipsEnabledTest::checkUseHttpsWithFipsEnabled);
    }


    private static void checkUseHttpsWithFipsEnabled(JenkinsRule r) {
        S3BlobStoreConfig descriptor = S3BlobStoreConfig.get();
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckUseHttp(true).kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckUseHttp(false).kind);
    }
}
