package io.jenkins.plugins.artifact_manager_jclouds.s3;

import io.jenkins.plugins.artifact_manager_jclouds.JCloudsArtifactManagerFactory;
import java.io.IOException;
import jenkins.model.ArtifactManagerFactory;

import org.jenkinsci.plugins.workflow.ArtifactManagerTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LogRecorder;
import org.jvnet.hudson.test.junit.jupiter.RealJenkinsExtension;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

import java.util.logging.Level;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.testcontainers.DockerClientFactory;

abstract class AbstractIntegrationTest {

    protected static final String CONTAINER_NAME = "jenkins";
    protected static final String CONTAINER_PREFIX = "ci/";

    @RegisterExtension
    protected final RealJenkinsExtension rr = new RealJenkinsExtension().javaOptions("-Xmx150m").withDebugPort(8000).withDebugSuspend(true);

    @SuppressWarnings("unused")
    protected final LogRecorder loggerRule = new LogRecorder().recordPackage(JCloudsArtifactManagerFactory.class, Level.FINE);

    @BeforeAll
    static void beforeAll() {
        // Beyond just isDockerAvailable, verify the OS:
        try {
            assumeTrue(DockerClientFactory.instance().client().infoCmd().exec().getOsType().equals("linux"), "expect to run Docker on Linux containers");
        } catch (Exception x) {
            assumeTrue(false, "does not look like Docker is available: " + x);
        }
    }

    private static S3Client client() throws IOException {
        return S3BlobStoreConfig.get().getAmazonS3ClientBuilderWithCredentials().build();
    }

    protected static ArtifactManagerFactory getArtifactManagerFactory(Boolean deleteArtifacts, Boolean deleteStashes) {
        return new JCloudsArtifactManagerFactory(new CustomBehaviorBlobStoreProvider(new S3BlobStore(), deleteArtifacts, deleteStashes));
    }

    protected static void _artifactArchiveAndDelete(JenkinsRule jenkinsRule) throws Throwable {
        createBucketWithAwsClient("artifact-archive-and-delete");
        ArtifactManagerTest.artifactArchiveAndDelete(jenkinsRule, getArtifactManagerFactory(true, null), true);
    }

    protected static void createBucketWithAwsClient(String bucketName) throws IOException {
        var config = S3BlobStoreConfig.get();
        config.setContainer(bucketName);
        client().createBucket(CreateBucketRequest.builder().bucket(bucketName).build());
    }

    protected static void _artifactArchive(JenkinsRule jenkinsRule) throws Throwable {
        createBucketWithAwsClient("artifact-archive");
        assertThat(client().headBucket(HeadBucketRequest.builder().bucket("artifact-archive").build()).sdkHttpResponse().isSuccessful(), is(true));
        ArtifactManagerTest.artifactArchive(jenkinsRule, getArtifactManagerFactory(null, null), true);
    }

    protected static void _artifactStashAndDelete(JenkinsRule jenkinsRule) throws Throwable {
        createBucketWithAwsClient("artifact-stash-and-delete");
        ArtifactManagerTest.artifactStashAndDelete(jenkinsRule, getArtifactManagerFactory(null, true), true);
    }

    protected static void _canCreateBucket(JenkinsRule r) throws Throwable {
        String testBucketName = "jenkins-ci-data";
        var config = S3BlobStoreConfig.get();
        Bucket createdBucket = config.createS3Bucket(testBucketName);
        assertEquals(testBucketName, createdBucket.name());
        assertThat(client().headBucket(HeadBucketRequest.builder().bucket(testBucketName).build()).sdkHttpResponse().isSuccessful(), is(true));
    }

    protected static void _artifactStash(JenkinsRule jenkinsRule) throws Throwable {
        createBucketWithAwsClient("artifact-stash");
        ArtifactManagerTest.artifactStash(jenkinsRule, getArtifactManagerFactory(null, null), true);
    }

    @Test
    void canCreateBucket() throws Throwable {
        rr.runRemotely(AbstractIntegrationTest::_canCreateBucket);
    }

    @Test
    void artifactArchive() throws Throwable {
        rr.runRemotely(AbstractIntegrationTest::_artifactArchive);
    }

    @Test
    void artifactArchiveAndDelete() throws Throwable {
        rr.runRemotely(AbstractIntegrationTest::_artifactArchiveAndDelete);
    }

    @Test
    void artifactStash() throws Throwable {
        rr.runRemotely(AbstractIntegrationTest::_artifactStash);
    }

    @Test
    void artifactStashAndDelete() throws Throwable {
        rr.runRemotely(AbstractIntegrationTest::_artifactStashAndDelete);
    }
}
