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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.remoting.Callable;
import io.jenkins.plugins.artifact_manager_jclouds.BlobStoreProvider.HttpMethod;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import jenkins.util.VirtualFile;
import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.MutableBlobMetadata;
import org.jclouds.blobstore.domain.PageSet;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.blobstore.options.ListContainerOptions;
import static org.jclouds.blobstore.options.ListContainerOptions.Builder.*;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * <a href="https://jclouds.apache.org/start/blobstore/">JClouds BlobStore Guide</a>
 */
@Restricted(NoExternalUse.class)
public class JCloudsVirtualFile extends VirtualFile {

    private static final long serialVersionUID = -5126878907895121335L;

    private static final Logger LOGGER = Logger.getLogger(JCloudsVirtualFile.class.getName());

    @NonNull
    private BlobStoreProvider provider;
    @NonNull
    private final String container;
    @NonNull
    private final String key;
    @CheckForNull
    private transient Blob blob;
    @CheckForNull
    private transient BlobStoreContext context;

    public JCloudsVirtualFile(@NonNull BlobStoreProvider provider, @NonNull String container, @NonNull String key) {
        this.provider = provider;
        this.container = container;
        this.key = key;
        assert !key.isEmpty();
        assert !key.startsWith("/");
        assert !key.endsWith("/");
    }

    /**
     * Build jclouds blob context that is the base for all operations
     */
    @Restricted(NoExternalUse.class) // testing only
    BlobStoreContext getContext() throws IOException {
        if (context == null) {
            context = provider.getContext();
        }
        return context;
    }

    private String getContainer() {
        return container;
    }

    /**
     * Returns the full name, directories included
     */
    private String getKey() {
        return key;
    }

    /**
     * Returns the base name
     */
    @Override
    public String getName() {
        return key.replaceFirst(".+/", "");
    }

    private Blob getBlob() throws IOException {
        if (blob == null) {
            LOGGER.log(Level.FINE, "checking for existence of blob {0} / {1}", new Object[] {container, key});
            blob = getContext().getBlobStore().getBlob(getContainer(), getKey());
            if (blob == null) {
                blob = getContext().getBlobStore().blobBuilder(getKey()).build();
                blob.getMetadata().setContainer(getContainer());
            }
        }
        return blob;
    }

    @Override
    public URI toURI() {
        return provider.toURI(container, key);
    }

    @Override
    public URL toExternalURL() throws IOException {
        return provider.toExternalURL(getBlob(), HttpMethod.GET);
    }

    @Override
    public VirtualFile getParent() {
        // undefined to go outside …/artifacts
        return new JCloudsVirtualFile(provider, getContainer(), key.replaceFirst("/[^/]+$", ""));
    }

    @Override
    public boolean isDirectory() throws IOException {
        String keyS = key + "/";
        CacheFrame frame = findCacheFrame(keyS);
        if (frame != null) {
            LOGGER.log(Level.FINE, "cache hit on directory status of {0} / {1}", new Object[] {container, key});
            String relSlash = keyS.substring(frame.root.length()); // "" or "sub/dir/"
            return frame.children.keySet().stream().anyMatch(f -> f.startsWith(relSlash));
        }
        LOGGER.log(Level.FINE, "checking directory status {0} / {1}", new Object[] {container, key});
        return !getContext().getBlobStore().list(getContainer(), prefix(key + "/")).isEmpty();
    }

    @Override
    public boolean isFile() throws IOException {
        CacheFrame frame = findCacheFrame(key);
        if (frame != null) {
            String rel = key.substring(frame.root.length());
            CachedMetadata metadata = frame.children.get(rel);
            LOGGER.log(Level.FINE, "cache hit on file status of {0} / {1}", new Object[] {container, key});
            return metadata != null;
        }
        LOGGER.log(Level.FINE, "checking file status {0} / {1}", new Object[] {container, key});
        return getBlob().getMetadata().getSize() != null;
    }

    @Override
    public boolean exists() throws IOException {
        return isDirectory() || isFile();
    }

    /**
     * List all the blobs under this one
     *
     * @return a stream of blobs StorageMetadata
     */
    private Stream<StorageMetadata> listStorageMetadata(boolean recursive) throws IOException {
        ListContainerOptions options = prefix(key + "/");
        if (recursive) {
            options.recursive();
        }
        PageSetIterable it = new PageSetIterable(getContext().getBlobStore(), getContainer(), options);
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(it, Spliterator.ORDERED), false);
    }

    @Override
    public VirtualFile[] list() throws IOException {
        String keyS = key + "/";
        CacheFrame frame = findCacheFrame(keyS);
        if (frame != null) {
            LOGGER.log(Level.FINE, "cache hit on listing of {0} / {1}", new Object[] {container, key});
            String relSlash = keyS.substring(frame.root.length()); // "" or "sub/dir/"
            return frame.children.keySet().stream(). // filenames relative to frame root
                filter(f -> f.startsWith(relSlash)). // those inside this dir
                map(f -> f.substring(relSlash.length()).replaceFirst("/.+", "")). // just the file simple name, or direct subdir name
                distinct(). // ignore duplicates if have multiple files under one direct subdir
                map(simple -> new JCloudsVirtualFile(provider, container, keyS + simple)). // direct children
                toArray(VirtualFile[]::new);
        }
        VirtualFile[] list = listStorageMetadata(false)
                .map(meta -> new JCloudsVirtualFile(provider, getContainer(), meta.getName().replaceFirst("/$", "")))
                .toArray(VirtualFile[]::new);
        LOGGER.log(Level.FINEST, "Listing files from {0} {1}: {2}",
                new String[] { getContainer(), getKey(), Arrays.toString(list) });
        return list;
    }

    @Override
    public VirtualFile child(String name) {
        return new JCloudsVirtualFile(provider, getContainer(), key + "/" + name);
    }

    @Override
    public long length() throws IOException {
        CacheFrame frame = findCacheFrame(key);
        if (frame != null) {
            String rel = key.substring(frame.root.length());
            CachedMetadata metadata = frame.children.get(rel);
            LOGGER.log(Level.FINE, "cache hit on length of {0} / {1}", new Object[] {container, key});
            return metadata != null ? metadata.length : 0;
        }
        LOGGER.log(Level.FINE, "checking length {0} / {1}", new Object[] {container, key});
        MutableBlobMetadata metadata = getBlob().getMetadata();
        Long size = metadata == null ? Long.valueOf(0) : metadata.getSize();
        return size == null ? 0 : size;
    }

    @Override
    public long lastModified() throws IOException {
        CacheFrame frame = findCacheFrame(key);
        if (frame != null) {
            String rel = key.substring(frame.root.length());
            CachedMetadata metadata = frame.children.get(rel);
            LOGGER.log(Level.FINE, "cache hit on lastModified of {0} / {1}", new Object[] {container, key});
            return metadata != null ? metadata.lastModified : 0;
        }
        LOGGER.log(Level.FINE, "checking modification time {0} / {1}", new Object[] {container, key});
        MutableBlobMetadata metadata = getBlob().getMetadata();
        return metadata == null || metadata.getLastModified() == null ? 0 : metadata.getLastModified().getTime();
    }

    @Override
    public boolean canRead() throws IOException {
        return true;
    }

    @Override
    public InputStream open() throws IOException {
        LOGGER.log(Level.FINE, "reading {0} / {1}", new Object[] {container, key});
        if (isDirectory()) {
            // That is what java.io.FileInputStream.open throws
            throw new FileNotFoundException(String.format("%s/%s (Is a directory)", getContainer(), getKey()));
        }
        if (!isFile()) {
            throw new FileNotFoundException(
                    String.format("%s/%s (No such file or directory)", getContainer(), getKey()));
        }
        return getBlob().getPayload().openStream();
    }

    /**
     * An Iterator for JClouds PageSet
     */
    @Restricted(NoExternalUse.class)
    static class PageSetIterable implements Iterator<StorageMetadata> {
        private final BlobStore blobStore;
        private final String container;
        private ListContainerOptions options;
        private PageSet<? extends StorageMetadata> set;
        private Iterator<? extends StorageMetadata> iterator;

        PageSetIterable(@NonNull BlobStore blobStore, @NonNull String container,
                @NonNull ListContainerOptions options) {
            this.blobStore = blobStore;
            this.container = container;
            advanceList(options);
        }

        @Override
        public boolean hasNext() {
            if (iterator.hasNext()) {
                return true;
            }
            String marker = set.getNextMarker();
            if (marker == null) {
                return false;
            }
            advanceList(options.afterMarker(marker));
            return iterator.hasNext();
        }

        @Override
        public StorageMetadata next() {
            if (hasNext()) {
                return iterator.next();
            } else {
                throw new NoSuchElementException();
            }
        }

        /**
         * Unsupported operation
         */
        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }

        private void advanceList(ListContainerOptions options) {
            LOGGER.log(Level.FINE, "listing {0}: {1}", new Object[] {container, options});
            this.options = options;
            this.set = blobStore.list(container, options);
            this.iterator = set.iterator();
        }
    }

    /**
     * Cache of metadata collected during {@link #run}.
     * Keys are {@link #container}.
     * Values are a stack of cache frames, one per nested {@link #run} call.
     */
    private static final ThreadLocal<Map<String, Deque<CacheFrame>>> cache = ThreadLocal.withInitial(HashMap::new);

    private static final class CacheFrame {
        /** {@link #key} of the root virtual file plus a trailing {@code /} */
        final String root;
        /**
         * Information about all known (recursive) child <em>files</em> (not directories).
         * Keys are {@code /}-separated relative paths.
         * If the root itself happened to be a file, that information is not cached.
         */
        final Map<String, CachedMetadata> children;
        CacheFrame(String root, Map<String, CachedMetadata> children) {
            this.root = root;
            this.children = children;
        }
    }

    /**
     * Record that a given file exists.
     */
    private static final class CachedMetadata {
        final long length, lastModified;
        CachedMetadata(long length, long lastModified) {
            this.length = length;
            this.lastModified = lastModified;
        }
    }

    @Override
    public <V> V run(Callable<V, IOException> callable) throws IOException {
        LOGGER.log(Level.FINE, "enter cache {0} / {1}", new Object[] {container, key});
        Deque<CacheFrame> stack = cacheFrames();
        Map<String, CachedMetadata> saved = new HashMap<>();
        int prefixLength = key.length() + /* / */1;
        listStorageMetadata(true).forEach(sm -> {
            Long length = sm.getSize();
            if (length != null) {
                Date lastModified = sm.getLastModified();
                saved.put(sm.getName().substring(prefixLength), new CachedMetadata(length, lastModified != null ? lastModified.getTime() : 0));
            }
        });
        stack.push(new CacheFrame(key + "/", saved));
        try {
            LOGGER.log(Level.FINE, "using cache {0} / {1}: {2} file entries", new Object[] {container, key, saved.size()});
            return callable.call();
        } finally {
            LOGGER.log(Level.FINE, "exit cache {0} / {1}", new Object[] {container, key});
            stack.pop();
        }
    }

    private Deque<CacheFrame> cacheFrames() {
        return cache.get().computeIfAbsent(container, c -> new ArrayDeque<>());
    }

    /** Finds a cache frame whose {@link CacheFrame#root} is a prefix of the given {@link #key} or {@code /}-appended variant. */
    private @CheckForNull CacheFrame findCacheFrame(String key) {
        return cacheFrames().stream().filter(frame -> key.startsWith(frame.root)).findFirst().orElse(null);
    }

}
