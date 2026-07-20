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

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;

import org.apache.commons.lang.RandomStringUtils;
import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.BlobStoreContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LogRecorder;

import io.jenkins.plugins.artifact_manager_jclouds.JCloudsVirtualFile;
import io.jenkins.plugins.aws.global_configuration.CredentialsAwsGlobalConfiguration;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

@WithJenkins
abstract class S3AbstractTest {

    private static final String S3_BUCKET = System.getenv("S3_BUCKET");
    protected static final String S3_DIR = System.getenv("S3_DIR");
    private static final String S3_REGION = System.getenv("S3_REGION");

    @TempDir
    protected File tmp;

    protected final LogRecorder loggerRule = new LogRecorder();

    protected JenkinsRule j;

    protected BlobStoreContext context;
    protected BlobStore blobStore;
    private String prefix;


    protected S3BlobStore provider;

    @BeforeAll
    static void beforeAll() {
        assumeTrue(S3_BUCKET != null, "define $S3_BUCKET as explained in README");
        assumeTrue(S3_DIR != null, "define $S3_DIR as explained in README");

        try (S3Client client = S3Client.create()) {
            assumeTrue(client.headBucket(HeadBucketRequest.builder().bucket(S3_BUCKET).build()).sdkHttpResponse().isSuccessful());
        } catch (SdkClientException x) {
            assumeTrue(false, "failed to connect to S3 with current credentials: " + x);
        }
    }

    @BeforeEach
    void beforeEach(JenkinsRule rule) throws Exception {
        j = rule;

        provider = new S3BlobStore();
        S3BlobStoreConfig config = S3BlobStoreConfig.get();
        config.setContainer(S3_BUCKET);

        CredentialsAwsGlobalConfiguration credentialsConfig = CredentialsAwsGlobalConfiguration.get();
        credentialsConfig.setRegion(S3_REGION);

        loggerRule.recordPackage(JCloudsVirtualFile.class, Level.FINE);

        // run each test under its own dir
        prefix = generateUniquePrefix();
        config.setPrefix(prefix);

        context = provider.getContext();

        blobStore = context.getBlobStore();
    }


    @AfterEach
    public void afterEach() throws Exception {
        if (context != null) {
            context.close();
        }
        JCloudsVirtualFile.delete(provider, blobStore, prefix);
    }

    protected static String getContainer() {
        return S3_BUCKET;
    }

    /**
     * To run each test in its own subdir
     */
    protected static String generateUniquePrefix() {
        return String.format("%s%s-%s/", S3_DIR, ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT),
                RandomStringUtils.randomAlphabetic(4).toLowerCase());
    }

    protected String getPrefix() {
        return prefix;
    }
}
