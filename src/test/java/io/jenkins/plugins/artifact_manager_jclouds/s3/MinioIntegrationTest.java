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
import java.io.IOException;
import jenkins.model.Jenkins;

import static org.hamcrest.Matchers.is;
import org.jenkinsci.plugins.workflow.ArtifactManagerTest;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.MinIOContainer;
import org.junit.Assume;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.RealJenkinsRule;
import org.testcontainers.DockerClientFactory;

public class MinioIntegrationTest extends AbstractIntegrationTest {
    private static final String ACCESS_KEY = "supersecure";
    private static final String SECRET_KEY = "donttell";
    private static final String REGION = "us-east-1";
    
    private static MinIOContainer minioServer;
    private static String minioServiceEndpoint;


    @BeforeClass
    public static void setUpClass() throws Exception {
        // Beyond just isDockerAvailable, verify the OS:
        try {
            Assume.assumeThat("expect to run Docker on Linux containers", DockerClientFactory.instance().client().infoCmd().exec().getOsType(), is("linux"));
        } catch (Exception x) {
            Assume.assumeNoException("does not look like Docker is available", x);
        }
        minioServer = new MinIOContainer("minio/minio:RELEASE.2025-04-22T22-12-26Z")
                .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
                .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
                .withLogConsumer(outputFrame -> System.out.println(outputFrame.getUtf8String()));
        minioServer.start();

        Integer mappedPort = minioServer.getMappedPort(9000); //.getFirstMappedPort();
        Testcontainers.exposeHostPorts(mappedPort);
        minioServiceEndpoint = String.format("%s:%s", minioServer.getHost(), mappedPort);
//        minioServiceEndpoint = "127.0.0.1:9000"; //
        
        image = ArtifactManagerTest.prepareImage();
    }
    
    @AfterClass
    public static void shutDownClass() {
        if (minioServer != null && minioServer.isRunning()) {
            minioServer.stop();
        }
        if(client != null) {
            client.close();
        }
    }
    
    private static void setUp(String minioServiceEndpoint) throws IOException {
        provider = new S3BlobStore();
        CredentialsAwsGlobalConfiguration credentialsConfig = CredentialsAwsGlobalConfiguration.get();
        credentialsConfig.setRegion(REGION);
        CredentialsProvider.lookupStores(Jenkins.get())
                .iterator()
                .next()
                .addCredentials(Domain.global(), new AWSCredentialsImpl(CredentialsScope.GLOBAL, "MinioIntegrationTest", ACCESS_KEY, SECRET_KEY, "MinioIntegrationTest"));
        credentialsConfig.setCredentialsId("MinioIntegrationTest");
        
        config = S3BlobStoreConfig.get();
        config.setContainer(CONTAINER_NAME);
        config.setPrefix(CONTAINER_PREFIX);
        config.setCustomEndpoint(minioServiceEndpoint);
        config.setUseHttp(true);
        config.setUsePathStyleUrl(true);
        config.setDisableSessionToken(true);
        client = config.getAmazonS3ClientBuilderWithCredentials().build();
    }

    private static final class WithMinioServiceEndpoint implements RealJenkinsRule.Step {
        private final String minioServiceEndpoint;
        private final RealJenkinsRule.Step delegate;
        WithMinioServiceEndpoint(RealJenkinsRule.Step delegate) {
            // Serialize the endpoint into the step sent to the real JVM:
            this.minioServiceEndpoint = MinioIntegrationTest.minioServiceEndpoint;
            this.delegate = delegate;
        }
        @Override public void run(JenkinsRule r) throws Throwable {
            setUp(minioServiceEndpoint);
            delegate.run(r);
        }
    }
    protected RealJenkinsRule.Step getStep(RealJenkinsRule.Step delegate) {
        return new WithMinioServiceEndpoint(delegate);
    }

}

