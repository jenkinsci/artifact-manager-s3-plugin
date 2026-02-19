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

package io.jenkins.plugins.artifact_manager_jclouds;

import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.domain.Blob;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.net.URL;

import static org.junit.Assert.*;

public class DisablePresignedUrlsTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    private MockBlobStore mockBlobStore;
    private BlobStore blobStore;

    @Before
    public void setup() throws Exception {
        mockBlobStore = new MockBlobStore();
        BlobStoreContext ctx = mockBlobStore.getContext();
        blobStore = ctx.getBlobStore();
        blobStore.createContainerInLocation(null, mockBlobStore.getContainer());
    }

    private JCloudsVirtualFile createVirtualFileWithBlob(String key, String content) {
        Blob blob = blobStore.blobBuilder(key).payload(content).build();
        blobStore.putBlob(mockBlobStore.getContainer(), blob);
        return new JCloudsVirtualFile(mockBlobStore, mockBlobStore.getContainer(), key);
    }

    @Test
    public void defaultProviderDoesNotDisablePresignedUrls() {
        assertFalse("Default isDisablePresignedUrls should be false", mockBlobStore.isDisablePresignedUrls());
    }

    @Test
    public void baseProviderDefaultDoesNotDisablePresignedUrls() {
        // Verify the BlobStoreProvider base class default
        MockBlobStore fresh = new MockBlobStore();
        assertFalse(fresh.isDisablePresignedUrls());
    }

    @Test
    public void toExternalURLReturnsUrlWhenPresignedUrlsEnabled() throws Exception {
        mockBlobStore.setDisablePresignedUrls(false);
        JCloudsVirtualFile vf = createVirtualFileWithBlob("test-key", "test content");

        URL url = vf.toExternalURL();
        assertNotNull("toExternalURL should return a URL when presigned URLs are enabled", url);
    }

    @Test
    public void toExternalURLReturnsNullWhenPresignedUrlsDisabled() throws Exception {
        mockBlobStore.setDisablePresignedUrls(true);
        JCloudsVirtualFile vf = createVirtualFileWithBlob("test-key", "test content");

        URL url = vf.toExternalURL();
        assertNull("toExternalURL should return null when presigned URLs are disabled", url);
    }

    @Test
    public void openStillWorksWhenPresignedUrlsDisabled() throws Exception {
        mockBlobStore.setDisablePresignedUrls(true);
        JCloudsVirtualFile vf = createVirtualFileWithBlob("test-key", "test content");

        assertTrue("File should still be readable", vf.isFile());
        assertNotNull("open() should still work for streaming through the controller", vf.open());
    }

    @Test
    public void setDisablePresignedUrlsToggle() {
        mockBlobStore.setDisablePresignedUrls(true);
        assertTrue(mockBlobStore.isDisablePresignedUrls());

        mockBlobStore.setDisablePresignedUrls(false);
        assertFalse(mockBlobStore.isDisablePresignedUrls());
    }
}
