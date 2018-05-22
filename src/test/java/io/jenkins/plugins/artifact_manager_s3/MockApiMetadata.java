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
import java.net.URL;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import org.apache.commons.io.IOUtils;
import org.apache.http.ExceptionLogger;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.bootstrap.HttpServer;
import org.apache.http.impl.bootstrap.ServerBootstrap;
import org.apache.http.protocol.HttpContext;
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
import org.jclouds.blobstore.util.BlobUtils;
import org.jclouds.domain.Location;
import org.kohsuke.MetaInfServices;

/**
 * A mock provider allowing control of all operations.
 * Otherwise akin to a simplified version of {@link TransientApiMetadata}.
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

    // TODO any clean way to get the instance out of Guice?
    // Possible to override BlobBuilderImpl to intercept blob creation from JCloudsArtifactManager
    // but not obvious how to tie that instance to anything else.
    static URL baseURL;

    /** Like {@link TransientStorageStrategy}. */
    public static final class MockStrategy implements LocalStorageStrategy {

        private final Map<String, Map<String, Blob>> blobsByContainer = new HashMap<>();
        private final BlobUtils blobUtils;

        @Inject
        public MockStrategy(BlobUtils blobUtils) throws Exception {
            this.blobUtils = blobUtils;
            HttpServer server = ServerBootstrap.bootstrap().
                registerHandler("*", (HttpRequest request, HttpResponse response, HttpContext context) -> {
                    String method = request.getRequestLine().getMethod();
                    Matcher m = Pattern.compile("/([^/]+)/(.+)").matcher(request.getRequestLine().getUri());
                    if (!m.matches()) {
                        throw new IllegalStateException();
                    }
                    String container = m.group(1);
                    String key = m.group(2);
                    switch (method) {
                        case "GET": {
                            Map<String, Blob> blobs = blobsByContainer.get(container);
                            if (blobs == null) {
                                response.setStatusCode(404);
                                return;
                            }
                            Blob blob = blobs.get(key);
                            if (blob == null) {
                                response.setStatusCode(404);
                                return;
                            }
                            byte[] data = IOUtils.toByteArray(blob.getPayload().openStream());
                            response.setStatusCode(200);
                            response.setEntity(new ByteArrayEntity(data));
                            LOGGER.log(Level.INFO, "Serving {0} bytes from {1}:{2}", new Object[] {data.length, container, key});
                            return;
                        } case "PUT": {
                            Map<String, Blob> blobs = blobsByContainer.computeIfAbsent(container, __ -> new HashMap<>());
                            Blob blob = blobs.computeIfAbsent(key, __ -> blobUtils.blobBuilder().name(key).build());
                            HttpEntity entity = ((HttpEntityEnclosingRequest) request).getEntity();
                            byte[] data = IOUtils.toByteArray(entity.getContent());
                            blob.setPayload(data);
                            blob.getMetadata().setSize((long) data.length);
                            blob.getMetadata().setContainer(container);
                            response.setStatusCode(204);
                            LOGGER.log(Level.INFO, "Uploaded {0} bytes to {1}:{2}", new Object[] {data.length, container, key});
                            return;
                        } default: {
                            throw new IllegalStateException();
                        }
                    }
                }).
                setExceptionLogger(ExceptionLogger.STD_ERR).
                create();
            server.start();
            baseURL = new URL("http://" + server.getInetAddress().getHostName() + ":" + server.getLocalPort() + "/");
            LOGGER.log(Level.INFO, "Mock server running at {0}", baseURL);
        }

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
            return blobsByContainer.get(container).keySet();
        }

        @Override
        public Blob getBlob(String containerName, String blobName) {
            return blobsByContainer.get(containerName).get(blobName);
        }

        @Override
        public String putBlob(String containerName, Blob blob) throws IOException {
            blobsByContainer.get(containerName).put(blob.getMetadata().getName(), blob);
            return null;
        }

        @Override
        public void removeBlob(String container, String key) {
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
