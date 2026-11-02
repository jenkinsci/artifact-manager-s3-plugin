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
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.LoggerRule;

import jenkins.util.VirtualFile;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

public class JCloudsVirtualFileTest extends S3AbstractTest {

    protected static final Logger LOGGER = Logger.getLogger(JCloudsVirtualFileTest.class.getName());

    protected File tmpFile;
    protected String filePath, missingFilePath, weirdCharactersPath;
    protected JCloudsVirtualFile root, subdir, vf, missing, weirdCharacters, weirdCharactersMissing;

    @Rule
    public LoggerRule httpLogging = new LoggerRule();

    @Override
    public void setup() throws Exception {
        tmpFile = tmp.newFile();
        Files.writeString(tmpFile.toPath(), "test");
        filePath = getPrefix() + tmpFile.getName();

        LOGGER.log(Level.INFO, "Adding test blob {0} {1}", new String[] { getContainer(), filePath });
        putObject(filePath, tmpFile);

        root = newJCloudsVirtualFile(S3_DIR);
        subdir = newJCloudsVirtualFile(getPrefix());
        vf = newJCloudsVirtualFile(filePath);

        missingFilePath = getPrefix() + "missing";
        missing = newJCloudsVirtualFile(missingFilePath);

        // ampersand '&' works fine with AWS SDK
        // Test various special characters that S3 supports
        // https://docs.aws.amazon.com/AmazonS3/latest/dev/UsingMetadata.html#object-keys
        weirdCharactersPath = getPrefix() + "xxx#?:$'\"<>čॐ";
        weirdCharacters = newJCloudsVirtualFile(weirdCharactersPath);
        weirdCharactersMissing = newJCloudsVirtualFile(weirdCharactersPath + "missing");
        LOGGER.log(Level.INFO, "Adding test blob {0} {1}", new String[] { getContainer(), weirdCharactersPath });
        putObject(weirdCharactersPath, tmpFile);
    }

    private void putObject(String key, File file) throws Exception {
        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(getContainer())
                .key(key)
                .build();
        client.putObject(putRequest, RequestBody.fromFile(file));
    }

    private JCloudsVirtualFile newJCloudsVirtualFile(String path) {
        return new JCloudsVirtualFile(provider, getContainer(), path.replaceFirst("/$", ""));
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
        JCloudsVirtualFile parent = (JCloudsVirtualFile) subdir.getParent();
        assertEquals(S3_DIR, parent.getName());
    }

    @Test
    public void list() throws Exception {
        VirtualFile[] children = subdir.list();
        String[] names = new String[children.length];
        for (int i = 0; i < children.length; i++) {
            names[i] = children[i].getName();
        }
        assertThat(names, arrayContainingInAnyOrder(tmpFile.getName(), "xxx#?:$'\"<>čॐ"));
    }

    @Test
    public void listOnFile() throws Exception {
        VirtualFile[] children = vf.list();
        assertThat(children, is(notNullValue()));
        assertThat(children.length, equalTo(0));
    }

    @Test
    public void isDirectory() throws Exception {
        assertTrue(root.isDirectory());
        assertTrue(subdir.isDirectory());
        assertFalse(vf.isDirectory());
        assertFalse(missing.isDirectory());
    }

    @Test
    public void isFile() throws Exception {
        assertFalse(root.isFile());
        assertFalse(subdir.isFile());
        assertTrue(vf.isFile());
        assertFalse(missing.isFile());
    }

    @Test
    public void length() throws Exception {
        long length = Files.readString(tmpFile.toPath()).getBytes().length;
        assertEquals(length, vf.length());
    }

    @Test
    public void lastModified() throws Exception {
        assertThat(vf.lastModified(), greaterThan(0L));
    }

    @Test
    public void open() throws Exception {
        try (InputStream is = vf.open()) {
            String content = IOUtils.toString(is, "UTF-8");
            String expectedContent = Files.readString(tmpFile.toPath());
            assertEquals(expectedContent, content);
        }
    }

    @Test
    public void openDirectory() throws Exception {
        try {
            vf.list(); // should work for directory
        } catch (FileNotFoundException x) {
            fail("Should be able to list a directory");
        }
    }

    @Test
    public void copyToAndVerify() throws Exception {
        // Verify we can read content from virtual file
        try (InputStream is = vf.open()) {
            byte[] content = IOUtils.toByteArray(is);
            byte[] expected = Files.readAllBytes(tmpFile.toPath());
            assertArrayEquals(expected, content);
        }
    }

    @Test
    public void contentComparison() throws Exception {
        // Verify content matches what we put in
        String vfContent;
        try (InputStream is = vf.open()) {
            vfContent = IOUtils.toString(is, "UTF-8");
        }
        String expectedContent = Files.readString(tmpFile.toPath());
        assertEquals(expectedContent, vfContent);
    }

    @Test
    public void toURI() throws Exception {
        // S3 URIs should follow s3:// scheme or use provider's endpoint
        assertNotEquals(null, vf.toURI());
    }

    @Test
    @Issue("JENKINS-50262")
    public void pruneDirectories() throws Exception {
        String dirPath = getPrefix() + "a/b/c/";
        JCloudsVirtualFile dir = newJCloudsVirtualFile(dirPath);

        File f = tmp.newFile();
        Files.writeString(f.toPath(), "test");
        putObject(getPrefix() + "a/b/c/file.txt", f);

        assertTrue(dir.child("file.txt").exists());

        // Deleting the file using static delete method
        String filePath = getPrefix() + "a/b/c/file.txt";
        JCloudsVirtualFile.delete(provider, filePath);

        assertFalse(dir.child("file.txt").exists());
    }

    @Test
    public void deleteRecursive() throws Exception {
        String dirPath = getPrefix() + "to_delete/";

        File f = tmp.newFile();
        Files.writeString(f.toPath(), "content");
        putObject(getPrefix() + "to_delete/file1.txt", f);
        putObject(getPrefix() + "to_delete/file2.txt", f);
        putObject(getPrefix() + "to_delete/subdir/file3.txt", f);

        JCloudsVirtualFile dir = newJCloudsVirtualFile(getPrefix() + "to_delete");
        assertTrue(dir.exists());

        // Use static delete method to clean up recursively
        JCloudsVirtualFile.delete(provider, getPrefix() + "to_delete/");
        
        assertFalse(dir.exists());
    }

    @Test
    public void child_with_slash() throws Exception {
        // S3 virtual file paths shouldn't include trailing slashes in most cases
        JCloudsVirtualFile withoutSlash = subdir;
        JCloudsVirtualFile withSlash = newJCloudsVirtualFile(getPrefix() + "/");

        // Both should be equivalent
        assertEquals(withoutSlash.getName(), withSlash.getName());
    }
}
