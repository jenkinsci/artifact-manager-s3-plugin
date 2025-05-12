package io.jenkins.plugins.artifact_manager_jclouds.s3;

import io.jenkins.plugins.artifact_manager_jclouds.JCloudsArtifactManagerFactory;
import jenkins.model.ArtifactManagerFactory;
import org.jenkinsci.plugins.workflow.ArtifactManagerTest;
import org.jenkinsci.test.acceptance.docker.DockerImage;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LoggerRule;
import org.jvnet.hudson.test.RealJenkinsRule;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

import java.util.logging.Level;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;

public abstract class AbstractIntegrationTest {

    protected static final String CONTAINER_NAME = "jenkins";
    protected static final String CONTAINER_PREFIX = "ci/";

    protected static DockerImage image;

    protected static S3BlobStoreConfig config;
    protected static S3Client client;
    protected static S3BlobStore provider;

    @Rule
    public RealJenkinsRule rr = new RealJenkinsRule().javaOptions("-Xmx150m").withDebugPort(8000).withDebugSuspend(true);

    @Rule
    public LoggerRule loggerRule = new LoggerRule().recordPackage(JCloudsArtifactManagerFactory.class, Level.FINE);

    protected static ArtifactManagerFactory getArtifactManagerFactory(Boolean deleteArtifacts, Boolean deleteStashes) {
        return new JCloudsArtifactManagerFactory(new CustomBehaviorBlobStoreProvider(provider, deleteArtifacts, deleteStashes));
    }

    protected static void _artifactArchiveAndDelete(JenkinsRule jenkinsRule) throws Throwable {
        createBucketWithAwsClient("artifact-archive-and-delete");
        ArtifactManagerTest.artifactArchiveAndDelete(jenkinsRule, getArtifactManagerFactory(true, null), true, image);
    }

    protected static void createBucketWithAwsClient(String bucketName) {
        config.setContainer(bucketName);
        client.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());
    }

    protected static void _artifactArchive(JenkinsRule jenkinsRule) throws Throwable {
        createBucketWithAwsClient("artifact-archive");
        assertThat(client.headBucket(HeadBucketRequest.builder().bucket("artifact-archive").build()).sdkHttpResponse().isSuccessful(), is(true));
        ArtifactManagerTest.artifactArchive(jenkinsRule, getArtifactManagerFactory(null, null), true, image);
    }

    protected static void _artifactStashAndDelete(JenkinsRule jenkinsRule) throws Throwable {
        createBucketWithAwsClient("artifact-stash-and-delete");
        ArtifactManagerTest.artifactStashAndDelete(jenkinsRule, getArtifactManagerFactory(null, true), true, image);
    }

    protected static void _canCreateBucket(JenkinsRule r) throws Throwable {
        String testBucketName = "jenkins-ci-data";
        Bucket createdBucket = config.createS3Bucket(testBucketName);
        assertEquals(testBucketName, createdBucket.name());
        assertThat(client.headBucket(HeadBucketRequest.builder().bucket(testBucketName).build()).sdkHttpResponse().isSuccessful(), is(true));
    }

    protected static void _artifactStash(JenkinsRule jenkinsRule) throws Throwable {
        createBucketWithAwsClient("artifact-stash");
        ArtifactManagerTest.artifactStash(jenkinsRule, getArtifactManagerFactory(null, null), true, image);
    }

    protected abstract RealJenkinsRule.Step getStep(RealJenkinsRule.Step delegate);

    @Test
    public void canCreateBucket() throws Throwable {
        rr.then(getStep(AbstractIntegrationTest::_canCreateBucket));
    }

    @Test
    public void artifactArchive() throws Throwable {
        rr.then(getStep(AbstractIntegrationTest::_artifactArchive));
    }

    @Test
    public void artifactArchiveAndDelete() throws Throwable {
        rr.then(getStep(AbstractIntegrationTest::_artifactArchiveAndDelete));
    }

    @Test
    public void artifactStash() throws Throwable {
        rr.then(getStep(AbstractIntegrationTest::_artifactStash));
    }

    @Test
    public void artifactStashAndDelete() throws Throwable {
        rr.then(getStep(AbstractIntegrationTest::_artifactStashAndDelete));
    }

}
