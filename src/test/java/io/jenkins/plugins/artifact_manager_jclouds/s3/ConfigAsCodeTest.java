/*
 * The MIT License
 *
 * Copyright 2018 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.jenkins.plugins.artifact_manager_jclouds.s3;

import io.jenkins.plugins.artifact_manager_jclouds.JCloudsArtifactManagerFactory;
import io.jenkins.plugins.aws.global_configuration.CredentialsAwsGlobalConfiguration;
import java.util.List;
import jenkins.model.ArtifactManagerConfiguration;
import jenkins.model.ArtifactManagerFactory;
import org.jenkinsci.plugins.casc.ConfigurationAsCode;
import org.junit.Test;
import static org.junit.Assert.*;
import org.junit.Rule;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;

public class ConfigAsCodeTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    @Issue("JENKINS-52304")
    @Test
    public void smokes() throws Exception {
        ConfigurationAsCode.get().configure(ConfigAsCodeTest.class.getResource("configuration-as-code.yml").toString());
        List<ArtifactManagerFactory> artifactManagerFactories = ArtifactManagerConfiguration.get().getArtifactManagerFactories();
        assertEquals(1, artifactManagerFactories.size());
        JCloudsArtifactManagerFactory mgr = (JCloudsArtifactManagerFactory) artifactManagerFactories.get(0);
        assertEquals(S3BlobStore.class, mgr.getProvider().getClass());
        assertEquals("us-east-1", CredentialsAwsGlobalConfiguration.get().getRegion());
        assertEquals("jenkins_data/", S3BlobStoreConfig.get().getPrefix());
    }

}
