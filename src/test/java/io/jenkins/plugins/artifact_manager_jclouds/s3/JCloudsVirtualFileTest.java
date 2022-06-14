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

import io.jenkins.plugins.artifact_manager_jclouds.JCloudsVirtualFile;
import io.jenkins.plugins.aws.global_configuration.CredentialsAwsGlobalConfiguration;
import static org.hamcrest.Matchers.*;
import static org.jclouds.blobstore.options.ListContainerOptions.Builder.*;
import static org.junit.Assert.*;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.PageSet;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.rest.internal.InvokeHttpMethod;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.LoggerRule;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ListObjectsV2Result;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import java.net.ProtocolException;

import jenkins.util.VirtualFile;
import org.jclouds.http.HttpResponseException;

public class JCloudsVirtualFileTest extends S3AbstractTest {

    protected File tmpFile;
    protected String filePath, missingFilePath, weirdCharactersPath;
    protected JCloudsVirtualFile root, subdir, vf, missing, weirdCharacters, weirdCharactersMissing;
    @Rule
    public LoggerRule httpLogging = new LoggerRule();

    @Override
    public void setup() throws Exception {
        tmpFile = tmp.newFile();
        FileUtils.writeStringToFile(tmpFile, "test");
        filePath = getPrefix() + tmpFile.getName();
        Blob blob = blobStore.blobBuilder(filePath).payload(tmpFile).build();

        LOGGER.log(Level.INFO, "Adding test blob {0} {1}", new String[] { getContainer(), filePath });
        putBlob(blob);

        root = newJCloudsBlobStore(S3_DIR);
        subdir = newJCloudsBlobStore(getPrefix());
        vf = newJCloudsBlobStore(filePath);

        missingFilePath = getPrefix() + "missing";
        missing = newJCloudsBlobStore(missingFilePath);

        // ampersand '&' fails the tests
        // it works using the aws-sdk directly so we can just assume it's a jclouds issue
        // https://docs.aws.amazon.com/AmazonS3/latest/dev/UsingMetadata.html#object-keys
        weirdCharactersPath = getPrefix() + "xxx#?:$'\"<>čॐ";
        weirdCharacters = newJCloudsBlobStore(weirdCharactersPath);
        weirdCharactersMissing = newJCloudsBlobStore(weirdCharactersPath + "missing");
        LOGGER.log(Level.INFO, "Adding test blob {0} {1}", new String[] { getContainer(), weirdCharactersPath });
        putBlob(blobStore.blobBuilder(weirdCharactersPath).payload(tmpFile).build());
    }

    /** Working around an apparent server flake. */
    private void putBlob(Blob blob) {
        for (int i = 0; i < 5; i++) {
            try {
                blobStore.putBlob(getContainer(), blob);
                return;
            } catch (HttpResponseException x) {
                if (x.getCause() instanceof ProtocolException && i < 4) {
                    x.printStackTrace();
                    continue;
                }
                throw x;
            }
        }
    }

    private JCloudsVirtualFile newJCloudsBlobStore(String path) {
        return new JCloudsVirtualFile(new S3BlobStore(), getContainer(), path.replaceFirst("/$", ""));
    }

    @Test
    public void child() throws Exception {
        assertTrue(subdir.child(tmpFile.getName()).exists());
        assertFalse(subdir.child(missing.getName()).exists());
    }

    @Test
    public void exists() throws Exception {
        assertTrue(root.exists());
        assertTrue(subdir.exists());
        assertTrue(vf.exists());
        assertFalse(missing.exists());
        assertTrue(weirdCharacters.exists());
        assertFalse(weirdCharactersMissing.exists());
    }

    @Test
    public void getName() throws Exception {
        String[] s = getPrefix().split("/");
        assertEquals(s[s.length - 1], subdir.getName());
        assertEquals(tmpFile.getName(), vf.getName());
        assertEquals("missing", missing.getName());
    }

    @Test
    public void getParent() throws Exception {
        assertEquals(root, subdir.getParent().getParent());
        assertEquals(subdir, vf.getParent());
        assertEquals(subdir, missing.getParent());
    }

    @Test
    public void isDirectory() throws Exception {
        assertTrue(root.isDirectory());
        assertTrue(subdir.isDirectory());
        assertFalse(vf.isDirectory());
        assertFalse(missing.isDirectory());
        assertFalse(weirdCharacters.isDirectory());
        assertFalse(weirdCharactersMissing.isDirectory());

        // currently fails with AuthorizationException due to ampersand see above
        // assertFalse(newJCloudsBlobStore(getPrefix() + "/chartest/xxx&").isDirectory());

        // but this succeeds
        // final AmazonS3 s3 = AmazonS3ClientBuilder.defaultClient();
        // ListObjectsV2Result listObjectsV2 = s3.listObjectsV2(getContainer(), getPrefix() +
        // "/chartest/xxx#?:$&'\"<>čॐ");
        // ObjectListing listObjects = s3.listObjects(getContainer(), getPrefix() + "/chartest/xxx#?:$&'\"<>čॐ");
    }

    @Test
    public void isFile() throws Exception {
        assertFalse(root.isFile());
        assertFalse(subdir.isFile());
        assertTrue(vf.isFile());
        assertFalse(missing.isFile());
        assertTrue(weirdCharacters.isFile());
        assertFalse(weirdCharactersMissing.isFile());
    }

    @Test
    public void lastModified() throws Exception {
        assertEquals(0, root.lastModified());
        assertEquals(0, subdir.lastModified());
        assertNotEquals(0, vf.lastModified());
        assertEquals(0, missing.lastModified());
    }

    @Test
    public void length() throws Exception {
        assertEquals(0, root.length());
        assertEquals(0, subdir.length());
        assertEquals(tmpFile.length(), vf.length());
        assertEquals(0, missing.length());
    }

    private void assertVirtualFileArrayEquals(VirtualFile[] expected, VirtualFile[] actual) {
        assertArrayEquals("Expected: " + Arrays.toString(expected) + " Actual: " + Arrays.toString(actual), expected,
                actual);
    }

    @Test
    public void list() throws Exception {
        assertVirtualFileArrayEquals(new JCloudsVirtualFile[] { vf, weirdCharacters }, subdir.list());
        assertVirtualFileArrayEquals(new JCloudsVirtualFile[0], vf.list());
        assertVirtualFileArrayEquals(new JCloudsVirtualFile[0], missing.list());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void listGlob() throws Exception {
        assertThat(subdir.list("**/**"), arrayContainingInAnyOrder(vf.getName(), weirdCharacters.getName()));
        assertArrayEquals(new String[] { vf.getName() }, subdir.list(tmpFile.getName().substring(0, 4) + "*"));
        assertArrayEquals(new String[0], subdir.list("**/something**"));
        assertArrayEquals(new String[0], vf.list("**/**"));
        assertArrayEquals(new String[0], missing.list("**/**"));
    }

    @Test
    public void pagedListing() throws Exception {
        for (int i = 0; i < 10; i++) {
            String iDir = getPrefix() + "sprawling/i" + i + "/";
            for (int j = 0; j < 10; j++) {
                for (int k = 0; k < 10; k++) {
                    putBlob(blobStore.blobBuilder(iDir + "j" + j + "/k" + k).payload(new byte[0]).build());
                }
            }
            putBlob(blobStore.blobBuilder(iDir + "extra").payload(new byte[0]).build());
            LOGGER.log(Level.INFO, "added 101 blobs to {0}", iDir);
        }
        httpLogging.record(InvokeHttpMethod.class, Level.FINE);
        httpLogging.capture(1000);
        Logger.getLogger(InvokeHttpMethod.class.getName()).addHandler(new Handler() {
            {setLevel(Level.FINE);}
            int count;
            @Override public void publish(LogRecord record) {
                if (record.getMessage().contains("invoking GetBucketLocation")) {
                    new Exception("calling GetBucketLocation #" + count++).printStackTrace();
                    if (count > 1) {
                        throw new IllegalStateException("should only ever have to call GetBucketLocation once");
                    }
                }
            }
            @Override public void flush() {}
            @Override public void close() throws SecurityException {}
        });
        // Default list page size for S3 is 1000 blobs; we have 1010 plus the two created for all tests, so should hit a second page.
        assertThat(subdir.list("sprawling/**/k3", null, true), iterableWithSize(100));
        assertEquals("calls GetBucketLocation then ListBucket, advance to …/sprawling/i9/j8/k8, ListBucket again", 3, httpLogging.getRecords().size());
    }

    @Test
    public void open() throws Exception {
        try (InputStream is = subdir.open()) {
            fail("Should not open a dir");
        } catch (FileNotFoundException e) {
            // expected
        }
        try (InputStream is = missing.open()) {
            fail("Should not open a missing file");
        } catch (FileNotFoundException e) {
            // expected
        }
        try (InputStream is = vf.open()) {
            assertEquals(FileUtils.readFileToString(tmpFile), IOUtils.toString(is));
        }
    }

    @Test
    public void toURI() throws Exception {
        assertEquals(String.format("https://%s.s3.amazonaws.com/%s", getContainer(), urlEncodeParts(getPrefix().replaceFirst("/$", ""))), subdir.toURI().toString());
        assertEquals(String.format("https://%s.s3.amazonaws.com/%s", getContainer(), urlEncodeParts(filePath)), vf.toURI().toString());
        // weird chars
        String stuff = "xxx#?:$&'\"<>čॐ";
        assertEquals(String.format("https://%s.s3.amazonaws.com/%s", getContainer(), urlEncodeParts(stuff)), newJCloudsBlobStore(stuff).toURI().toString());
        // region
        CredentialsAwsGlobalConfiguration.get().setRegion("us-west-1");
        assertEquals(String.format("https://%s.s3.us-west-1.amazonaws.com/what/ever", getContainer()), newJCloudsBlobStore("what/ever").toURI().toString());
    }
    private static String urlEncodeParts(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8").replaceAll("%2F", "/");
    }

    @Test
    @Issue({ "JENKINS-50591", "JCLOUDS-1401" })
    public void testAmpersand() throws Exception {
        String key = getPrefix() + "xxx#?:&$'\"<>čॐ";

        try {
            putBlob(blobStore.blobBuilder(key).payload("test").build());

            final AmazonS3 s3 = AmazonS3ClientBuilder.defaultClient();
            ListObjectsV2Result result = s3.listObjectsV2(getContainer(), key);
            List<S3ObjectSummary> objects = result.getObjectSummaries();
            assertThat(objects, not(empty()));

            // fails with
            // org.jclouds.rest.AuthorizationException: The request signature we calculated does not match the signature
            // you provided. Check your key and signing method.
            PageSet<? extends StorageMetadata> list = blobStore.list(getContainer(), prefix(key));
            assertThat(list, not(empty()));
        } finally {
            blobStore.removeBlob(getContainer(), key);
        }
    }
}
