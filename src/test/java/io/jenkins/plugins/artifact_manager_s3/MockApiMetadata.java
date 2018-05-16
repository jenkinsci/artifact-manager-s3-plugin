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

import com.google.inject.AbstractModule;
import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import org.jclouds.apis.ApiMetadata;
import org.jclouds.apis.internal.BaseApiMetadata;
import org.jclouds.blobstore.BlobRequestSigner;
import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.LocalBlobRequestSigner;
import org.jclouds.blobstore.LocalStorageStrategy;
import org.jclouds.blobstore.TransientApiMetadata;
import org.jclouds.blobstore.TransientStorageStrategy;
import org.jclouds.blobstore.attr.ConsistencyModel;
import org.jclouds.blobstore.config.BlobStoreObjectModule;
import org.jclouds.blobstore.config.LocalBlobStore;
import org.jclouds.blobstore.config.TransientBlobStoreContextModule;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.BlobAccess;
import org.jclouds.blobstore.domain.ContainerAccess;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.blobstore.options.CreateContainerOptions;
import org.jclouds.blobstore.options.ListContainerOptions;
import org.jclouds.domain.Location;
import org.kohsuke.MetaInfServices;

/**
 * A mock provider allowing control of all operations.
 * Otherwise akin to a simplified version of {@link TransientApiMetadata}.
 */
@MetaInfServices(ApiMetadata.class)
public final class MockApiMetadata extends BaseApiMetadata {

    public MockApiMetadata() {
        this(new Builder());
    }

    private MockApiMetadata(Builder builder) {
        super(builder);
    }

    @Override
    public Builder toBuilder() {
        return new Builder().fromApiMetadata(this);
    }

    private static final class Builder extends BaseApiMetadata.Builder<Builder> {

        Builder() {
            id("mock");
            name("mock");
            identityName("mock");
            documentation(URI.create("about:nothing"));
            defaultIdentity("nobody");
            defaultCredential("anon");
            defaultEndpoint("http://nowhere.net/");
            view(BlobStoreContext.class);
            defaultModule(MockModule.class);
        }

        @Override
        protected Builder self() {
            return this;
        }

        @Override
        public ApiMetadata build() {
            return new MockApiMetadata(this);
        }

    }

    /** Like {@link TransientBlobStoreContextModule}. */
    public static final class MockModule extends AbstractModule {

        @Override
        protected void configure() {
            install(new BlobStoreObjectModule());
            bind(BlobStore.class).to(LocalBlobStore.class);
            bind(ConsistencyModel.class).toInstance(ConsistencyModel.STRICT);
            bind(LocalStorageStrategy.class).to(MockStrategy.class);
            bind(BlobRequestSigner.class).to(LocalBlobRequestSigner.class);
        }

    }

    /** Like {@link TransientStorageStrategy}. */
    public static final class MockStrategy implements LocalStorageStrategy {

        @Override
        public boolean containerExists(String container) {
            throw new UnsupportedOperationException(); // TODO
        }

        @Override
        public Collection<String> getAllContainerNames() {
            throw new UnsupportedOperationException(); // TODO
        }

        @Override
        public boolean createContainerInLocation(String container, Location location, CreateContainerOptions options) {
            throw new UnsupportedOperationException(); // TODO
        }

        @Override
        public ContainerAccess getContainerAccess(String container) {
            throw new UnsupportedOperationException(); // TODO
        }

        @Override
        public void setContainerAccess(String container, ContainerAccess access) {
            throw new UnsupportedOperationException(); // TODO
        }

        @Override
        public void deleteContainer(String container) {
            throw new UnsupportedOperationException(); // TODO
        }

        @Override
        public void clearContainer(String container) {
            throw new UnsupportedOperationException(); // TODO
        }

        @Override
        public void clearContainer(String container, ListContainerOptions options) {
            throw new UnsupportedOperationException(); // TODO
        }

        @Override
        public StorageMetadata getContainerMetadata(String container) {
            throw new UnsupportedOperationException(); // TODO
        }

        @Override
        public boolean blobExists(String container, String key) {
            throw new UnsupportedOperationException(); // TODO
        }

        @Override
        public Iterable<String> getBlobKeysInsideContainer(String container) throws IOException {
            throw new UnsupportedOperationException(); // TODO
        }

        @Override
        public Blob getBlob(String containerName, String blobName) {
            throw new UnsupportedOperationException(); // TODO
        }

        @Override
        public String putBlob(String containerName, Blob blob) throws IOException {
            throw new UnsupportedOperationException(); // TODO
        }

        @Override
        public void removeBlob(String container, String key) {
            throw new UnsupportedOperationException(); // TODO
        }

        @Override
        public BlobAccess getBlobAccess(String container, String key) {
            throw new UnsupportedOperationException(); // TODO
        }

        @Override
        public void setBlobAccess(String container, String key, BlobAccess access) {
            throw new UnsupportedOperationException(); // TODO
        }

        @Override
        public Location getLocation(String containerName) {
            throw new UnsupportedOperationException(); // TODO
        }

        @Override
        public String getSeparator() {
            return "/";
        }

    }

}
