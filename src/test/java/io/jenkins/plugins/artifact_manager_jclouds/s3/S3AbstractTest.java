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

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assume.assumeNoException;
import static org.junit.Assume.assumeThat;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;

import org.apache.commons.lang.RandomStringUtils;
import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.BlobStoreContext;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LoggerRule;

import io.jenkins.plugins.artifact_manager_jclouds.JCloudsVirtualFile;
import io.jenkins.plugins.aws.global_configuration.CredentialsAwsGlobalConfiguration;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

public abstract class S3AbstractTest {
    private static final String S3_BUCKET = System.getenv("S3_BUCKET");
    protected static final String S3_DIR = System.getenv("S3_DIR");
    private static final String S3_REGION = System.getenv("S3_REGION");

    protected S3BlobStore provider;

    @BeforeClass
    public static void live() {
        assumeThat("define $S3_BUCKET as explained in README", S3_BUCKET, notNullValue());
        assumeThat("define $S3_DIR as explained in README", S3_DIR, notNullValue());

        try (S3Client client = S3Client.create()) {
            assumeThat(client.headBucket(HeadBucketRequest.builder().bucket(S3_BUCKET).build()).sdkHttpResponse().isSuccessful(), is(true));
        } catch (SdkClientException x) {
            x.printStackTrace();
            assumeNoException("failed to connect to S3 with current credentials", x);
        }
    }
    
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Rule
    public LoggerRule loggerRule = new LoggerRule();

    @Rule
    public JenkinsRule j = new JenkinsRule();

    protected BlobStoreContext context;
    protected BlobStore blobStore;
    private String prefix;

    public static String getContainer() {
        return S3_BUCKET;
    }

    /**
     * To run each test in its own subdir
     */
    public static String generateUniquePrefix() {
        return String.format("%s%s-%s/", S3_DIR, ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT),
                RandomStringUtils.randomAlphabetic(4).toLowerCase());
    }

    protected String getPrefix() {
        return prefix;
    }

    @Before
    public void setupContext() throws Exception {

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

        setup();
    }

    public void setup() throws Exception {
    }

    @After
    public void tearDown() throws Exception {
        if (context != null) {
            context.close();
        }
    }

    @After
    public void deleteBlobs() throws Exception {
        JCloudsVirtualFile.delete(provider, blobStore, prefix);
    }

}
