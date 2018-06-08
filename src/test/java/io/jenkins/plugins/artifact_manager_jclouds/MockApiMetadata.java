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

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Strings;
import static com.google.common.collect.Iterables.transform;
import static com.google.common.collect.Sets.filter;
import static com.google.common.collect.Sets.newTreeSet;
import com.google.inject.AbstractModule;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.inject.Inject;
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
import org.jclouds.blobstore.domain.BlobBuilder;
import org.jclouds.blobstore.domain.BlobMetadata;
import org.jclouds.blobstore.domain.ContainerAccess;
import org.jclouds.blobstore.domain.MultipartPart;
import org.jclouds.blobstore.domain.MultipartUpload;
import org.jclouds.blobstore.domain.MutableStorageMetadata;
import org.jclouds.blobstore.domain.PageSet;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.blobstore.domain.StorageType;
import org.jclouds.blobstore.domain.internal.MutableStorageMetadataImpl;
import org.jclouds.blobstore.domain.internal.PageSetImpl;
import org.jclouds.blobstore.options.CopyOptions;
import org.jclouds.blobstore.options.CreateContainerOptions;
import org.jclouds.blobstore.options.GetOptions;
import org.jclouds.blobstore.options.ListContainerOptions;
import org.jclouds.blobstore.options.PutOptions;
import org.jclouds.domain.Location;
import org.jclouds.io.Payload;
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
            bind(BlobStore.class).to(PatchedLocalBlobStore.class);
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

    /**
     * TODO delete when updating to 2.1.1 or 2.2.0
     * @see <a href="https://issues.apache.org/jira/browse/JCLOUDS-1422">JCLOUDS-1422</a>
     */
    public static final class PatchedLocalBlobStore implements BlobStore {

        private final LocalBlobStore delegate;

        @Inject
        public PatchedLocalBlobStore(LocalBlobStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public PageSet<? extends StorageMetadata> list(String containerName, ListContainerOptions options) {
            PageSet<? extends StorageMetadata> base = delegate.list(containerName, options);
            if (!options.isRecursive()) {
                String prefix = options.getPrefix();
                if (!Strings.isNullOrEmpty(prefix)) {
                    SortedSet<StorageMetadata> recursiveResult = new TreeSet<>(delegate.list(containerName, options.clone().recursive()));
                    SortedSet<StorageMetadata> processedResult = extractCommonPrefixes(recursiveResult, "/", prefix);
                    if (!processedResult.equals(new TreeSet<>(base))) {
                        LOGGER.log(Level.INFO, "JCLOUDS-1422: for list({0}, {1}) replacing {2} with {3}", new Object[] {containerName, options, summarize(base), summarize(processedResult)});
                        return new PageSetImpl<>(processedResult, /* TODO close enough for now */null);
                    }
                }
            }
            return base;
        }

        private String summarize(Collection<? extends StorageMetadata> list) {
            return list.stream().map(StorageMetadata::getName).collect(Collectors.joining(":"));
        }

        // from LocalBlobStore
        private static SortedSet<StorageMetadata> extractCommonPrefixes(SortedSet<StorageMetadata> contents, String delimiter, String prefix) {
            if (Strings.isNullOrEmpty(delimiter)) {
                return contents;
            }
            SortedSet<String> commonPrefixes = newTreeSet(transform(contents, new CommonPrefixes(prefix, delimiter)));
            commonPrefixes.remove(CommonPrefixes.NO_PREFIX);
            contents = newTreeSet(filter(contents, new DelimiterFilter(prefix, delimiter)));
            for (String o : commonPrefixes) {
                MutableStorageMetadata md = new MutableStorageMetadataImpl();
                md.setType(StorageType.RELATIVE_PATH);
                if (prefix != null) {
                    o = prefix + o;
                }
                md.setName(o + delimiter);
                contents.add(md);
            }
            return contents;
        }
        private static class CommonPrefixes implements Function<StorageMetadata, String> {
            private final String prefix;
            private final String delimiter;
            public static final String NO_PREFIX = "NO_PREFIX";
            CommonPrefixes(String prefix, String delimiter) {
                this.prefix = prefix;
                this.delimiter = delimiter;
            }
            @Override
            public String apply(StorageMetadata metadata) {
                String working = metadata.getName();
                if (prefix != null) {
                    if (working.startsWith(prefix)) {
                        working = working.substring(prefix.length());
                    } else {
                        return NO_PREFIX;
                    }
                }
                if (working.contains(delimiter)) {
                    return working.substring(0, working.indexOf(delimiter));
                } else {
                    return NO_PREFIX;
                }
            }
        }
        private static class DelimiterFilter implements Predicate<StorageMetadata> {
            private final String prefix;
            private final String delimiter;
            DelimiterFilter(String prefix, String delimiter) {
                this.prefix = prefix;
                this.delimiter = delimiter;
            }
            @Override
            public boolean apply(StorageMetadata metadata) {
                String name = metadata.getName();
                if (prefix == null || prefix.isEmpty()) {
                    return !name.contains(delimiter);
                }
                if (name.startsWith(prefix)) {
                    String unprefixedName = name.substring(prefix.length());
                    if (unprefixedName.isEmpty()) {
                        return true;
                    }
                    return !unprefixedName.contains(delimiter);
                }
                return false;
            }
        }

        @Override
        public BlobStoreContext getContext() {
            return delegate.getContext();
        }
        @Override
        public BlobBuilder blobBuilder(String name) {
            return delegate.blobBuilder(name);
        }
        @Override
        public PageSet<? extends StorageMetadata> list(String containerName) {
            return delegate.list(containerName);
        }
        @Override
        public long countBlobs(String containerName) {
            return delegate.countBlobs(containerName);
        }
        @Override
        public long countBlobs(String containerName, ListContainerOptions options) {
            return delegate.countBlobs(containerName, options);
        }
        @Override
        public void clearContainer(String containerName) {
            delegate.clearContainer(containerName);
        }
        @Override
        public void clearContainer(String containerName, ListContainerOptions options) {
            delegate.clearContainer(containerName, options);
        }
        @Override
        public void deleteDirectory(String containerName, String directory) {
            delegate.deleteDirectory(containerName, directory);
        }
        @Override
        public boolean directoryExists(String containerName, String directory) {
            return delegate.directoryExists(containerName, directory);
        }
        @Override
        public void createDirectory(String containerName, String directory) {
            delegate.createDirectory(containerName, directory);
        }
        @Override
        public Blob getBlob(String containerName, String key) {
            return delegate.getBlob(containerName, key);
        }
        @Override
        public void deleteContainer(String containerName) {
            delegate.deleteContainer(containerName);
        }
        @Override
        public Set<? extends Location> listAssignableLocations() {
            return delegate.listAssignableLocations();
        }
        @Override
        public void removeBlob(String containerName, String key) {
            delegate.removeBlob(containerName, key);
        }
        @Override
        public void removeBlobs(String container, Iterable<String> names) {
            delegate.removeBlobs(container, names);
        }
        @Override
        public BlobAccess getBlobAccess(String container, String name) {
            return delegate.getBlobAccess(container, name);
        }
        @Override
        public void setBlobAccess(String container, String name, BlobAccess access) {
            delegate.setBlobAccess(container, name, access);
        }
        @Override
        public boolean deleteContainerIfEmpty(String containerName) {
            return delegate.deleteContainerIfEmpty(containerName);
        }
        @Override
        public boolean containerExists(String containerName) {
            return delegate.containerExists(containerName);
        }
        @Override
        public PageSet<? extends StorageMetadata> list() {
            return delegate.list();
        }
        @Override
        public boolean createContainerInLocation(Location location, String name) {
            return delegate.createContainerInLocation(location, name);
        }
        @Override
        public ContainerAccess getContainerAccess(String container) {
            return delegate.getContainerAccess(container);
        }
        @Override
        public void setContainerAccess(String container, ContainerAccess access) {
            delegate.setContainerAccess(container, access);
        }
        @Override
        public String putBlob(String containerName, Blob blob) {
            return delegate.putBlob(containerName, blob);
        }
        @Override
        public String copyBlob(String fromContainer, String fromName, String toContainer, String toName, CopyOptions options) {
            return delegate.copyBlob(fromContainer, fromName, toContainer, toName, options);
        }
        @Override
        public boolean blobExists(String containerName, String key) {
            return delegate.blobExists(containerName, key);
        }
        @Override
        public Blob getBlob(String containerName, String key, GetOptions options) {
            return delegate.getBlob(containerName, key, options);
        }
        @Override
        public BlobMetadata blobMetadata(String containerName, String key) {
            return delegate.blobMetadata(containerName, key);
        }
        @Override
        public String putBlob(String containerName, Blob blob, PutOptions options) {
            return delegate.putBlob(containerName, blob, options);
        }
        @Override
        public boolean createContainerInLocation(Location location, String container, CreateContainerOptions options) {
            return delegate.createContainerInLocation(location, container, options);
        }
        @Override
        public MultipartUpload initiateMultipartUpload(String container, BlobMetadata blobMetadata, PutOptions options) {
            return delegate.initiateMultipartUpload(container, blobMetadata, options);
        }
        @Override
        public void abortMultipartUpload(MultipartUpload mpu) {
            delegate.abortMultipartUpload(mpu);
        }
        @Override
        public String completeMultipartUpload(MultipartUpload mpu, List<MultipartPart> parts) {
            return delegate.completeMultipartUpload(mpu, parts);
        }
        @Override
        public MultipartPart uploadMultipartPart(MultipartUpload mpu, int partNumber, Payload payload) {
            return delegate.uploadMultipartPart(mpu, partNumber, payload);
        }
        @Override
        public List<MultipartPart> listMultipartUpload(MultipartUpload mpu) {
            return delegate.listMultipartUpload(mpu);
        }
        @Override
        public List<MultipartUpload> listMultipartUploads(String container) {
            return delegate.listMultipartUploads(container);
        }
        @Override
        public long getMinimumMultipartPartSize() {
            return delegate.getMinimumMultipartPartSize();
        }
        @Override
        public long getMaximumMultipartPartSize() {
            return delegate.getMaximumMultipartPartSize();
        }
        @Override
        public int getMaximumNumberOfParts() {
            return delegate.getMaximumNumberOfParts();
        }
        @Override
        public void downloadBlob(String container, String name, File destination) {
            delegate.downloadBlob(container, name, destination);
        }
        @Override
        public void downloadBlob(String container, String name, File destination, ExecutorService executor) {
            delegate.downloadBlob(container, name, destination, executor);
        }
        @Override
        public InputStream streamBlob(String container, String name) {
            return delegate.streamBlob(container, name);
        }
        @Override
        public InputStream streamBlob(String container, String name, ExecutorService executor) {
            return delegate.streamBlob(container, name, executor);
        }

    }

}
