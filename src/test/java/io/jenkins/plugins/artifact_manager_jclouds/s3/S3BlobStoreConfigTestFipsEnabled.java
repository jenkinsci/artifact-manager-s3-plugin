package io.jenkins.plugins.artifact_manager_jclouds.s3;

import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.FlagRule;
import org.jvnet.hudson.test.JenkinsRule;
import hudson.util.FormValidation;
import static org.junit.Assert.assertEquals;
import jenkins.security.FIPS140;


public class S3BlobStoreConfigTestFipsEnabled {

    @Rule
    public JenkinsRule j = new JenkinsRule();


    @ClassRule
    public static FlagRule<String> fipsFlag = FlagRule.systemProperty(FIPS140.class.getName() + ".COMPLIANCE", "true");


    @Test
    public void checkValidationUseHttpsWithFipsEnabled() {
        S3BlobStoreConfig descriptor = S3BlobStoreConfig.get();
        assertEquals(descriptor.doCheckUseHttp(true).kind , FormValidation.Kind.ERROR);
        assertEquals(descriptor.doCheckUseHttp(false).kind , FormValidation.Kind.OK);
    }
}
