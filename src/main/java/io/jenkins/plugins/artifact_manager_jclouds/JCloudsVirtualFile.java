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

import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import hudson.AbortException;
import hudson.remoting.Callable;
import io.jenkins.plugins.artifact_manager_jclouds.BlobStoreProvider.HttpMethod;
import jenkins.util.VirtualFile;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

/**
 * Implementation of {@link VirtualFile} backed by an S3-compatible blob store, accessed via the AWS SDK.
 */
@Restricted(NoExternalUse.class)
public class JCloudsVirtualFile extends VirtualFile {

    private static final long serialVersionUID = -5126878907895121335L;

    private static final Logger LOGGER = Logger.getLogger(JCloudsVirtualFile.class.getName());

    /** Maximum number of keys accepted per S3 DeleteObjects request. */
    private static final int DELETE_BATCH_SIZE = 1000;

    @NonNull
    private BlobStoreProvider provider;
    @NonNull
    private final String container;
    @NonNull
    private final String key;

    /** Cache of a successful (or negative) {@code headObject} call for this exact key. */
    @SuppressFBWarnings(value = "SE_TRANSIENT_FIELD_NOT_RESTORED",
            justification = "The metadata cache is intentionally rebuilt after deserialization.")
    private transient boolean metadataChecked;
    @CheckForNull
    private transient HeadObjectResponse metadata;

    public JCloudsVirtualFile(@NonNull BlobStoreProvider provider, @NonNull String container, @NonNull String key) {
        this.provider = provider;
        this.container = container;
        this.key = key;
        assert !key.isEmpty();
        assert !key.startsWith("/");
        assert !key.endsWith("/");
    }

    private JCloudsVirtualFile(@NonNull JCloudsVirtualFile related, @NonNull String key) {
        this(related.provider, related.container, key);
    }

    /**
     * Build the AWS SDK client that is the base for all operations. Callers are responsible for closing it.
     */
    @Restricted(NoExternalUse.class) // testing only
    S3Client getClient() throws IOException {
        return provider.getClient();
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

    private HeadObjectResponse getMetadata() throws IOException {
        if (!metadataChecked) {
            LOGGER.log(Level.FINE, "checking for existence of blob {0} / {1}", new Object[] {container, key});
            try (S3Client client = getClient()) {
                metadata = client.headObject(HeadObjectRequest.builder().bucket(container).key(key).build());
            } catch (NoSuchKeyException x) {
                metadata = null;
            } catch (S3Exception x) {
                if (x.statusCode() == 404) {
                    metadata = null;
                } else {
                    throw new IOException(x);
                }
            } catch (SdkException x) {
                throw new IOException(x);
            }
            metadataChecked = true;
        }
        return metadata;
    }

    @Override
    public URI toURI() {
        return provider.toURI(container, key);
    }

    @Override
    public URL toExternalURL() throws IOException {
        return provider.toExternalURL(container, key, null, HttpMethod.GET);
    }

    @Override
    public VirtualFile getParent() {
        // undefined to go outside …/artifacts
        return new JCloudsVirtualFile(this, key.replaceFirst("/[^/]+$", ""));
    }

    @Override
    public boolean isDirectory() throws IOException {
        String keyS = key + "/";
        CacheFrame frame = findCacheFrame(keyS);
        if (frame != null) {
            LOGGER.log(Level.FINER, "cache hit on directory status of {0} / {1}", new Object[] {container, key});
            String relSlash = keyS.substring(frame.root.length()); // "" or "sub/dir/"
            return frame.children.keySet().stream().anyMatch(f -> f.startsWith(relSlash));
        }
        LOGGER.log(Level.FINE, "checking directory status {0} / {1}", new Object[] {container, key});
        try (S3Client client = getClient()) {
            ListObjectsV2Request req = ListObjectsV2Request.builder().bucket(container).prefix(keyS).maxKeys(1).build();
            return client.listObjectsV2(req).keyCount() > 0;
        } catch (SdkException x) {
            throw new IOException(x);
        }
    }

    @Override
    public boolean isFile() throws IOException {
        CacheFrame frame = findCacheFrame(key);
        if (frame != null) {
            String rel = key.substring(frame.root.length());
            CachedMetadata cachedMetadata = frame.children.get(rel);
            LOGGER.log(Level.FINER, "cache hit on file status of {0} / {1}", new Object[] {container, key});
            return cachedMetadata != null;
        }
        LOGGER.log(Level.FINE, "checking file status {0} / {1}", new Object[] {container, key});
        return getMetadata() != null;
    }

    @Override
    public boolean exists() throws IOException {
        return isDirectory() || isFile();
    }

    /**
     * List all the objects one level under this one (files and direct “directories”, non-recursive).
     */
    private List<String> listDirectChildren() throws IOException {
        List<String> names = new ArrayList<>();
        try (S3Client client = getClient()) {
            ListObjectsV2Request req = ListObjectsV2Request.builder().bucket(container).prefix(key + "/").delimiter("/").build();
            ListObjectsV2Iterable pages = client.listObjectsV2Paginator(req);
            for (var page : pages) {
                for (S3Object obj : page.contents()) {
                    String name = obj.key().substring((key + "/").length()).replaceFirst("/$", "");
                    if (!name.contains("/")) {
                        names.add(name);
                    }
                }
                for (CommonPrefix cp : page.commonPrefixes()) {
                    names.add(cp.prefix().substring((key + "/").length()).replaceFirst("/$", ""));
                }
            }
        } catch (SdkException x) {
            throw new IOException(x);
        }
        return names;
    }

    @Override
    public VirtualFile[] list() throws IOException {
        String keyS = key + "/";
        CacheFrame frame = findCacheFrame(keyS);
        if (frame != null) {
            LOGGER.log(Level.FINER, "cache hit on listing of {0} / {1}", new Object[] {container, key});
            String relSlash = keyS.substring(frame.root.length()); // "" or "sub/dir/"
            return frame.children.keySet().stream(). // filenames relative to frame root
                filter(f -> f.startsWith(relSlash)). // those inside this dir
                map(f -> f.substring(relSlash.length()).replaceFirst("/.+", "")). // just the file simple name, or direct subdir name
                distinct(). // ignore duplicates if have multiple files under one direct subdir
                map(simple -> new JCloudsVirtualFile(this, keyS + simple)). // direct children
                toArray(VirtualFile[]::new);
        }
        VirtualFile[] list;
        try {
            list = listDirectChildren().stream()
                .map(name -> new JCloudsVirtualFile(this, key + "/" + name))
                .toArray(VirtualFile[]::new);
        } catch (RuntimeException x) {
            throw new IOException(x);
        }
        LOGGER.log(Level.FINEST, "Listing files from {0} {1}: {2}",
                new String[] { getContainer(), getKey(), Arrays.toString(list) });
        return list;
    }

    @Override
    public VirtualFile child(String name) {
        return new JCloudsVirtualFile(this, key + "/" + name);
    }

    @Override
    public long length() throws IOException {
        CacheFrame frame = findCacheFrame(key);
        if (frame != null) {
            String rel = key.substring(frame.root.length());
            CachedMetadata cachedMetadata = frame.children.get(rel);
            LOGGER.log(Level.FINER, "cache hit on length of {0} / {1}", new Object[] {container, key});
            return cachedMetadata != null ? cachedMetadata.length : 0;
        }
        LOGGER.log(Level.FINE, "checking length {0} / {1}", new Object[] {container, key});
        HeadObjectResponse head = getMetadata();
        return head == null || head.contentLength() == null ? 0 : head.contentLength();
    }

    @Override
    public long lastModified() throws IOException {
        CacheFrame frame = findCacheFrame(key);
        if (frame != null) {
            String rel = key.substring(frame.root.length());
            CachedMetadata cachedMetadata = frame.children.get(rel);
            LOGGER.log(Level.FINER, "cache hit on lastModified of {0} / {1}", new Object[] {container, key});
            return cachedMetadata != null ? cachedMetadata.lastModified : 0;
        }
        LOGGER.log(Level.FINE, "checking modification time {0} / {1}", new Object[] {container, key});
        HeadObjectResponse head = getMetadata();
        Instant lastModified = head == null ? null : head.lastModified();
        return lastModified == null ? 0 : lastModified.toEpochMilli();
    }

    @Override
    public boolean canRead() throws IOException {
        return true;
    }

    @SuppressFBWarnings(value = "SIC_INNER_SHOULD_BE_STATIC_ANON",
            justification = "The stream wrapper must close the client created for this object.")
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
        S3Client client = getClient();
        try {
            InputStream is = client.getObject(GetObjectRequest.builder().bucket(container).key(key).build());
            return new FilterInputStream(is) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        client.close();
                    }
                }
            };
        } catch (SdkException x) {
            client.close();
            throw new IOException(x);
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
        try (S3Client client = getClient()) {
            ListObjectsV2Request req = ListObjectsV2Request.builder().bucket(container).prefix(key + "/").build();
            ListObjectsV2Iterable pages = client.listObjectsV2Paginator(req);
            for (var page : pages) {
                for (S3Object obj : page.contents()) {
                    Long length = obj.size();
                    if (length != null) {
                        Instant lastModified = obj.lastModified();
                        saved.put(obj.key().substring(prefixLength), new CachedMetadata(length, lastModified != null ? lastModified.toEpochMilli() : 0));
                    }
                }
            }
        } catch (AwsServiceException | SdkClientException e) {
            if (e instanceof S3Exception s3x && s3x.statusCode() == 403) {
                throw new AbortException(String.format("Authorization failed: %s", e.getMessage()));
            }
            throw new IOException(e);
        }
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

    /**
     * Delete all blobs starting with a given prefix.
     */
    public static boolean delete(BlobStoreProvider provider, String prefix) throws IOException, InterruptedException {
        try (S3Client client = provider.getClient()) {
            List<String> paths = new ArrayList<>();
            ListObjectsV2Request req = ListObjectsV2Request.builder().bucket(provider.getContainer()).prefix(prefix).build();
            ListObjectsV2Iterable pages = client.listObjectsV2Paginator(req);
            for (var page : pages) {
                for (S3Object obj : page.contents()) {
                    String path = obj.key();
                    if (!path.startsWith(prefix)) {
                        LOGGER.warning(() -> path + " does not start with " + prefix);
                        continue;
                    }
                    paths.add(path);
                }
            }
            if (paths.isEmpty()) {
                LOGGER.log(Level.FINE, "nothing to delete under {0}", prefix);
                return false;
            }
            LOGGER.log(Level.FINE, "deleting {0} blobs under {1}", new Object[] {paths.size(), prefix});
            for (int i = 0; i < paths.size(); i += DELETE_BATCH_SIZE) {
                List<String> batch = paths.subList(i, Math.min(i + DELETE_BATCH_SIZE, paths.size()));
                List<ObjectIdentifier> ids = batch.stream().map(p -> ObjectIdentifier.builder().key(p).build()).collect(Collectors.toList());
                client.deleteObjects(DeleteObjectsRequest.builder()
                        .bucket(provider.getContainer())
                        .delete(d -> d.objects(ids))
                        .build());
            }
            return true;
        } catch (SdkException x) {
            throw new IOException(x);
        }
    }

}
