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

import com.cloudbees.jenkins.plugins.awscredentials.AWSCredentialsImpl;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.domains.Domain;
import io.jenkins.plugins.aws.global_configuration.CredentialsAwsGlobalConfiguration;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.jclouds.aws.domain.Region;
import org.jenkinsci.plugins.workflow.ArtifactManagerTest;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.RealJenkinsRule;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.util.Locale;

import static org.hamcrest.Matchers.is;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.S3;

public class LocalStackIntegrationTest extends AbstractIntegrationTest {
    private static String ACCESS_KEY;
    private static String SECRET_KEY;
    private static String REGION;

    private static String localStackServiceEndpoint;

    private static LocalStackContainer LOCALSTACK;


    @BeforeClass
    public static void setUpClass() throws Exception {
        // Beyond just isDockerAvailable, verify the OS:
        try {
            Assume.assumeThat("expect to run Docker on Linux containers", DockerClientFactory.instance().client().infoCmd().exec().getOsType(), is("linux"));
        } catch (Exception x) {
            Assume.assumeNoException("does not look like Docker is available", x);
        }

        LOCALSTACK = new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.4.0"))
                .withServices(S3);
        LOCALSTACK.start();
        Integer mappedPort = LOCALSTACK.getFirstMappedPort();
        Testcontainers.exposeHostPorts(mappedPort);

        localStackServiceEndpoint = LOCALSTACK.getEndpoint().getHost() + ":" + LOCALSTACK.getEndpoint().getPort();
        ACCESS_KEY = LOCALSTACK.getAccessKey();
        SECRET_KEY = LOCALSTACK.getSecretKey();
        REGION = LOCALSTACK.getRegion();
        
        image = ArtifactManagerTest.prepareImage();
    }
    
    @AfterClass
    public static void shutDownClass() {
        if (LOCALSTACK != null && LOCALSTACK.isRunning()) {
            LOCALSTACK.stop();
        }
        if (client != null) {
            client.close();
        }
    }
    
    private static void setUp(WithLocalStackServiceEndpoint withLocalStackServiceEndpoint) throws IOException {
        provider = new S3BlobStore();
        CredentialsAwsGlobalConfiguration credentialsConfig = CredentialsAwsGlobalConfiguration.get();
        credentialsConfig.setRegion(withLocalStackServiceEndpoint.region);
        CredentialsProvider.lookupStores(Jenkins.get())
                .iterator()
                .next()
                .addCredentials(Domain.global(), new AWSCredentialsImpl(CredentialsScope.GLOBAL, "LocalStackIntegrationTest", withLocalStackServiceEndpoint.accessKey,
                        withLocalStackServiceEndpoint.secretKey, "LocalStackIntegrationTest"));
        credentialsConfig.setCredentialsId("LocalStackIntegrationTest");
        
        config = S3BlobStoreConfig.get();
        config.setContainer(CONTAINER_NAME);
        config.setPrefix(CONTAINER_PREFIX);
        config.setCustomEndpoint(withLocalStackServiceEndpoint.localStackServiceEndpoint);
        config.setUseHttp(true);
        config.setUsePathStyleUrl(true);
        config.setDisableSessionToken(true);
        config.setCustomSigningRegion(StringUtils.isBlank(REGION)? Region.US_EAST_1.toLowerCase(Locale.US):REGION);
        client = config.getAmazonS3ClientBuilderWithCredentials().build();
    }

    private static final class WithLocalStackServiceEndpoint implements RealJenkinsRule.Step {
        private final String localStackServiceEndpoint;
        private final String accessKey;
        private final String secretKey;
        private final String region;
        private final RealJenkinsRule.Step delegate;
        WithLocalStackServiceEndpoint(RealJenkinsRule.Step delegate) {
            // Serialize the endpoint into the step sent to the real JVM:
            this.localStackServiceEndpoint = LocalStackIntegrationTest.localStackServiceEndpoint;
            this.accessKey = LocalStackIntegrationTest.ACCESS_KEY;
            this.secretKey = LocalStackIntegrationTest.SECRET_KEY;
            this.region = LocalStackIntegrationTest.REGION;
            this.delegate = delegate;
        }
        @Override public void run(JenkinsRule r) throws Throwable {
            setUp(this);
            delegate.run(r);
        }
    }

    protected RealJenkinsRule.Step getStep(RealJenkinsRule.Step delegate) {
        return new WithLocalStackServiceEndpoint(delegate);
    }

}

