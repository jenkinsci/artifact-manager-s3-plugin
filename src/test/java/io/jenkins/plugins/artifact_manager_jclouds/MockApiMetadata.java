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

import com.google.inject.AbstractModule;
import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.apache.commons.io.IOUtils;
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
import org.jclouds.io.Payloads;
import org.kohsuke.MetaInfServices;

/**
 * A mock provider allowing control of all operations.
 * Otherwise akin to a simplified version of {@link TransientApiMetadata}.
 * Whereas the stock {@code transient} provider would merely implement the full SPI,
 * we also want to allow particular metadata operations to fail or block at test-specified times.
 */
@MetaInfServices(ApiMetadata.class)
public final class MockApiMetadata extends BaseApiMetadata {

    private static final Logger LOGGER = Logger.getLogger(MockApiMetadata.class.getName());

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

    @FunctionalInterface
    interface GetBlobKeysInsideContainerHandler {
        Iterable<String> run() throws IOException;
    }

    private static final Map<String, GetBlobKeysInsideContainerHandler> getBlobKeysInsideContainerHandlers = new ConcurrentHashMap<>();

    static void handleGetBlobKeysInsideContainer(String container, GetBlobKeysInsideContainerHandler handler) {
        getBlobKeysInsideContainerHandlers.put(container, handler);
    }

    private static final Map<String, Runnable> removeBlobHandlers = new ConcurrentHashMap<>();

    static void handleRemoveBlob(String container, String key, Runnable handler) {
        removeBlobHandlers.put(container + '/' + key, handler);
    }

    /** Like {@link TransientStorageStrategy}. */
    public static final class MockStrategy implements LocalStorageStrategy {

        private final Map<String, Map<String, Blob>> blobsByContainer = new HashMap<>();

        @Override
        public boolean containerExists(String container) {
            return blobsByContainer.containsKey(container);
        }

        @Override
        public Collection<String> getAllContainerNames() {
            return blobsByContainer.keySet();
        }

        @Override
        public boolean createContainerInLocation(String container, Location location, CreateContainerOptions options) {
            return blobsByContainer.putIfAbsent(container, new HashMap<>()) == null;
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
            blobsByContainer.remove(container);
        }

        @Override
        public void clearContainer(String container) {
            blobsByContainer.get(container).clear();
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
            return blobsByContainer.get(container).containsKey(key);
        }

        @Override
        public Iterable<String> getBlobKeysInsideContainer(String container) throws IOException {
            GetBlobKeysInsideContainerHandler handler = getBlobKeysInsideContainerHandlers.remove(container);
            if (handler != null) {
                return handler.run();
            }
            return blobsByContainer.get(container).keySet();
        }

        @Override
        public Blob getBlob(String containerName, String blobName) {
            Blob blob = blobsByContainer.get(containerName).get(blobName);
            assert containerName.equals(blob.getMetadata().getContainer()) : blob;
            return blob;
        }

        @Override
        public String putBlob(String containerName, Blob blob) throws IOException {
            {
                // When called from LocalBlobStore.copyBlob, there is no container, and it uses an InputStreamPayload which cannot be reused.
                // TransientStorageStrategy has an elaborate createUpdatedCopyOfBlobInContainer here, but these two fixups seem to suffice.
                blob.getMetadata().setContainer(containerName);
                byte[] data = IOUtils.toByteArray(blob.getPayload().openStream());
                blob.getMetadata().setSize((long) data.length);
                blob.setPayload(Payloads.newByteArrayPayload(data));
            }
            blobsByContainer.get(containerName).put(blob.getMetadata().getName(), blob);
            return null;
        }

        @Override
        public void removeBlob(String container, String key) {
            Runnable handler = removeBlobHandlers.remove(container + '/' + key);
            if (handler != null) {
                handler.run();
                return;
            }
            blobsByContainer.get(container).remove(key);
        }

        @Override
        public BlobAccess getBlobAccess(String container, String key) {
            return BlobAccess.PRIVATE;
        }

        @Override
        public void setBlobAccess(String container, String key, BlobAccess access) {
            // ignore
        }

        @Override
        public Location getLocation(String containerName) {
            return null;
        }

        @Override
        public String getSeparator() {
            return "/";
        }

    }

}
