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

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.Bucket;
import com.amazonaws.services.s3.model.HeadBucketRequest;
import com.cloudbees.jenkins.plugins.awscredentials.AWSCredentialsImpl;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.domains.Domain;
import io.jenkins.plugins.artifact_manager_jclouds.JCloudsArtifactManagerFactory;
import io.jenkins.plugins.aws.global_configuration.CredentialsAwsGlobalConfiguration;
import java.io.IOException;
import java.time.Duration;
import java.util.logging.Level;
import jenkins.model.ArtifactManagerFactory;
import jenkins.model.Jenkins;
import static org.hamcrest.Matchers.is;
import org.jenkinsci.plugins.workflow.ArtifactManagerTest;
import org.jenkinsci.test.acceptance.docker.DockerImage;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import static org.junit.Assert.assertEquals;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LoggerRule;
import org.jvnet.hudson.test.RealJenkinsRule;
import org.testcontainers.DockerClientFactory;

public class MinioIntegrationTest {
    private static final String ACCESS_KEY = "supersecure";
    private static final String SECRET_KEY = "donttell";
    private static final String CONTAINER_NAME = "jenkins";
    private static final String CONTAINER_PREFIX = "ci/";
    private static final String REGION = "us-east-1";
    
    private static GenericContainer minioServer;
    private static String minioServiceEndpoint;
    private static DockerImage image;

    private static S3BlobStoreConfig config;
    private static AmazonS3 client;
    private static S3BlobStore provider;
    
    @Rule
    public RealJenkinsRule rr = new RealJenkinsRule().javaOptions("-Xmx150m");
    
    @Rule
    public LoggerRule loggerRule = new LoggerRule().recordPackage(JCloudsArtifactManagerFactory.class, Level.FINE);

    protected static ArtifactManagerFactory getArtifactManagerFactory(Boolean deleteArtifacts, Boolean deleteStashes) {
        return new JCloudsArtifactManagerFactory(new CustomBehaviorBlobStoreProvider(provider, deleteArtifacts, deleteStashes));
    }

    @BeforeClass
    public static void setUpClass() throws Exception {
        // Beyond just isDockerAvailable, verify the OS:
        try {
            Assume.assumeThat("expect to run Docker on Linux containers", DockerClientFactory.instance().client().infoCmd().exec().getOsType(), is("linux"));
        } catch (Exception x) {
            Assume.assumeNoException("does not look like Docker is available", x);
        }
        int port = 9000;
        minioServer = new GenericContainer("minio/minio")
                .withEnv("MINIO_ACCESS_KEY", ACCESS_KEY)
                .withEnv("MINIO_SECRET_KEY", SECRET_KEY)
                .withCommand("server /data")
                .withExposedPorts(port)
                .waitingFor(new HttpWaitStrategy()
                        .forPath("/minio/health/ready")
                        .forPort(port)
                        .withStartupTimeout(Duration.ofSeconds(10)));
        minioServer.start();

        Integer mappedPort = minioServer.getFirstMappedPort();
        Testcontainers.exposeHostPorts(mappedPort);
        minioServiceEndpoint = String.format("%s:%s", minioServer.getContainerIpAddress(), mappedPort);
        
        image = ArtifactManagerTest.prepareImage();
    }
    
    @AfterClass
    public static void shutDownClass() {
        if (minioServer != null && minioServer.isRunning()) {
            minioServer.stop();
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
    
    private static void createBucketWithAwsClient(String bucketName) {
        config.setContainer(bucketName);
        client.createBucket(bucketName);
    }
    
    @Test
    public void canCreateBucket() throws Throwable {
        rr.then(new WithMinioServiceEndpoint(MinioIntegrationTest::_canCreateBucket));
    }
    private static void _canCreateBucket(JenkinsRule r) throws Throwable {
        String testBucketName = "jenkins-ci-data";
        Bucket createdBucket = config.createS3Bucket(testBucketName);
        assertEquals(testBucketName, createdBucket.getName());
        client.headBucket(new HeadBucketRequest(testBucketName));
    }
    
    @Test
    public void artifactArchive() throws Throwable {
        rr.then(new WithMinioServiceEndpoint(MinioIntegrationTest::_artifactArchive));
    }
    private static void _artifactArchive(JenkinsRule jenkinsRule) throws Throwable {
        createBucketWithAwsClient("artifact-archive");
        ArtifactManagerTest.artifactArchive(jenkinsRule, getArtifactManagerFactory(null, null), true, image);
    }

    @Test
    public void artifactArchiveAndDelete() throws Throwable {
        rr.then(new WithMinioServiceEndpoint(MinioIntegrationTest::_artifactArchiveAndDelete));
    }
    private static void _artifactArchiveAndDelete(JenkinsRule jenkinsRule) throws Throwable {
        createBucketWithAwsClient("artifact-archive-and-delete");
        ArtifactManagerTest.artifactArchiveAndDelete(jenkinsRule, getArtifactManagerFactory(true, null), true, image);
    }
    
    @Test
    public void artifactStash() throws Throwable {
        rr.then(new WithMinioServiceEndpoint(MinioIntegrationTest::_artifactStash));
    }
    private static void _artifactStash(JenkinsRule jenkinsRule) throws Throwable {
        createBucketWithAwsClient("artifact-stash");
        ArtifactManagerTest.artifactStash(jenkinsRule, getArtifactManagerFactory(null, null), true, image);
    }

    @Test
    public void artifactStashAndDelete() throws Throwable {
        rr.then(new WithMinioServiceEndpoint(MinioIntegrationTest::_artifactStashAndDelete));
    }
    private static void _artifactStashAndDelete(JenkinsRule jenkinsRule) throws Throwable {
        createBucketWithAwsClient("artifact-stash-and-delete");
        ArtifactManagerTest.artifactStashAndDelete(jenkinsRule, getArtifactManagerFactory(null, true), true, image);
    }
}

