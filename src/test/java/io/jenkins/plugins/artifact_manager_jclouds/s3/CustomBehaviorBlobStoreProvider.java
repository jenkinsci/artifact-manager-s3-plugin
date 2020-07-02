/*
 * The MIT License
 *
 * Copyright 2019 CloudBees, Inc.
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

import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.artifact_manager_jclouds.BlobStoreProvider;
import io.jenkins.plugins.artifact_manager_jclouds.BlobStoreProviderDescriptor;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.domain.Blob;

public class CustomBehaviorBlobStoreProvider extends BlobStoreProvider {

    private final BlobStoreProvider delegate;
    private final Boolean deleteArtifacts, deleteStashes;

    CustomBehaviorBlobStoreProvider(BlobStoreProvider delegate, Boolean deleteArtifacts, Boolean deleteStashes) {
        this.delegate = delegate;
        this.deleteArtifacts = deleteArtifacts;
        this.deleteStashes = deleteStashes;
    }

    @Override
    public String getPrefix() {
        return delegate.getPrefix();
    }

    @Override
    public String getContainer() {
        return delegate.getContainer();
    }

    @Override
    public boolean isDeleteArtifacts() {
        return deleteArtifacts != null ? deleteArtifacts : delegate.isDeleteArtifacts();
    }

    @Override
    public boolean isDeleteStashes() {
        return deleteStashes != null ? deleteStashes : delegate.isDeleteStashes();
    }

    @Override
    public BlobStoreContext getContext() throws IOException {
        return delegate.getContext();
    }

    @Override
    public URI toURI(String container, String key) {
        return delegate.toURI(container, key);
    }

    @Override
    public URL toExternalURL(Blob blob, BlobStoreProvider.HttpMethod httpMethod) throws IOException {
        return delegate.toExternalURL(blob, httpMethod);
    }

    @Override
    public MultipartUploader initiateMultipartUpload(@NonNull Blob blob) throws IOException {
        return delegate.initiateMultipartUpload(blob);
    }

    @Override
    public BlobStoreProviderDescriptor getDescriptor() {
        return delegate.getDescriptor();
    }

}
