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
import org.jenkinsci.plugins.workflow.ArtifactManagerTest;
import org.jenkinsci.test.acceptance.docker.DockerImage;
import org.jenkinsci.test.acceptance.docker.fixtures.JavaContainer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import static org.junit.Assert.assertEquals;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.springframework.test.util.ReflectionTestUtils;

public class MinioIntegrationTest extends JavaContainer {
    private static final String ACCESS_KEY = "supersecure";
    private static final String SECRET_KEY = "donttell";
    private static final String CONTAINER_NAME = "jenkins";
    private static final String CONTAINER_PREFIX = "ci/";
    private static final String REGION = "us-east-1";
    
    private static GenericContainer minioServer;
    private static String minioServiceEndpoint;
    private static DockerImage image;

    private S3BlobStoreConfig config;
    private AmazonS3 client;    
    private S3BlobStore provider;
    private JCloudsArtifactManagerFactory artifactManagerFactory;
    
    @Rule
    public JenkinsRule jenkinsRule = new JenkinsRule();
    
    @BeforeClass
    public static void setUpClass() throws Exception {
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
        if (minioServer.isRunning()) {
            minioServer.stop();
        }
    }
    
    @Before
    public void setUp() throws IOException {
        provider = new S3BlobStore();
        CredentialsAwsGlobalConfiguration credentialsConfig = CredentialsAwsGlobalConfiguration.get();
        credentialsConfig.setRegion(REGION);
        CredentialsProvider.lookupStores(jenkinsRule.jenkins)
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
        artifactManagerFactory = new JCloudsArtifactManagerFactory(provider);
    }
    
    private void createBucketWithAwsClient(String bucketName) {
        config.setContainer(bucketName);
        client.createBucket(bucketName);
    }
    
    @Test
    public void canCreateBucket() throws Exception {
        String testBucketName = "jenkins-ci-data";
        Bucket createdBucket = config.createS3Bucket(testBucketName);
        assertEquals(testBucketName, createdBucket.getName());
        client.headBucket(new HeadBucketRequest(testBucketName));
    }
    
    @Test
    public void artifactArchive() throws Exception {
        createBucketWithAwsClient("artifact-archive");
        ArtifactManagerTest.artifactArchive(jenkinsRule, artifactManagerFactory, true, image);
    }

    @Test
    public void artifactArchiveAndDelete() throws Exception {
        createBucketWithAwsClient("artifact-archive-and-delete");
        ReflectionTestUtils.setField(config, "deleteArtifacts", true);
        ArtifactManagerTest.artifactArchiveAndDelete(jenkinsRule, artifactManagerFactory, true, image);
    }
    
    @Test(expected = AssertionError.class)
    public void artifactArchiveAndDeleteShouldFail() throws Exception {
        createBucketWithAwsClient("artifact-archive-and-delete-should-fail");
        ArtifactManagerTest.artifactArchiveAndDelete(jenkinsRule, artifactManagerFactory, true, image);
    }

    @Test
    public void artifactStash() throws Exception {
        createBucketWithAwsClient("artifact-stash");
        ArtifactManagerTest.artifactStash(jenkinsRule, artifactManagerFactory, /* TODO true → 400: Unsupported copy source parameter. Re-enable once JCLOUDS-1447 released. */false, image);
    }

    @Test
    public void artifactStashAndDelete() throws Exception {
        createBucketWithAwsClient("artifact-stash-and-delete");
        ReflectionTestUtils.setField(config, "deleteStashes", true);
        ArtifactManagerTest.artifactStashAndDelete(jenkinsRule, artifactManagerFactory, /* TODO ditto */false, image);
    }
    
    @Test(expected = AssertionError.class)
    public void artifactStashAndDeleteShouldFail() throws Exception {
        createBucketWithAwsClient("artifact-stash-and-delete-should-fail");
        ArtifactManagerTest.artifactStashAndDelete(jenkinsRule, artifactManagerFactory, /* TODO ditto */false, image);
    }
}
