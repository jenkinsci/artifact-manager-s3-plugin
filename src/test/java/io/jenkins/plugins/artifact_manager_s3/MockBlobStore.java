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

package io.jenkins.plugins.artifact_manager_s3;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import org.jclouds.ContextBuilder;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.domain.Blob;

public final class MockBlobStore extends BlobStoreProvider {

    private transient BlobStoreContext context;

    @Override
    public String getPrefix() {
        return "";
    }

    @Override
    public String getContainer() {
        return "container";
    }

    @Override
    public synchronized BlobStoreContext getContext() throws IOException {
        if (context == null) {
            context = ContextBuilder.newBuilder("mock").buildView(BlobStoreContext.class);
        }
        return context;
    }

    @Override
    public URI toURI(String container, String key) {
        return URI.create("mock://" + container + "/" + key);
    }

    @Override
    public URL toExternalURL(Blob blob, HttpMethod httpMethod) throws IOException {
        return new URL(MockApiMetadata.baseURL, blob.getMetadata().getContainer() + "/" + blob.getMetadata().getName());
    }

}
