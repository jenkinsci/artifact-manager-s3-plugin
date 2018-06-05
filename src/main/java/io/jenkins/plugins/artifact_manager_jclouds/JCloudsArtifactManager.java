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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.blobstore.options.CopyOptions;
import org.jclouds.blobstore.options.ListContainerOptions;
import org.jenkinsci.plugins.workflow.flow.StashManager;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.BuildListener;
import hudson.model.Computer;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.slaves.WorkspaceList;
import hudson.util.DirScanner;
import hudson.util.io.ArchiverFactory;
import io.jenkins.plugins.artifact_manager_jclouds.BlobStoreProvider.HttpMethod;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import jenkins.MasterToSlaveFileCallable;
import jenkins.model.ArtifactManager;
import jenkins.util.JenkinsJVM;
import jenkins.util.VirtualFile;
import org.apache.commons.io.IOUtils;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Artifact manager that stores files in a JClouds BlobStore using any of JClouds supported backends
 */
@Restricted(NoExternalUse.class)
public final class JCloudsArtifactManager extends ArtifactManager implements StashManager.StashAwareArtifactManager {

    private static final Logger LOGGER = Logger.getLogger(JCloudsArtifactManager.class.getName());

    private final BlobStoreProvider provider;

    private transient String key; // e.g. myorg/myrepo/master/123

    JCloudsArtifactManager(Run<?, ?> build, BlobStoreProvider provider) {
        this.provider = provider;
        onLoad(build);
    }

    private Object readResolve() {
        if (provider == null) {
            throw new IllegalStateException("Missing provider field");
        }
        return this;
    }

    @Override
    public void onLoad(Run<?, ?> build) {
        this.key = String.format("%s/%s", build.getParent().getFullName(), build.getNumber());
    }

    private String getBlobPath(String path) {
        return getBlobPath(key, path);
    }

    private String getBlobPath(String key, String path) {
        return String.format("%s%s/%s", provider.getPrefix(), key, path);
    }

    /*
     * This could be called multiple times
     */
    @Override
    public void archive(FilePath workspace, Launcher launcher, BuildListener listener, Map<String, String> artifacts)
            throws IOException, InterruptedException {
        LOGGER.log(Level.FINE, "Archiving from {0}: {1}", new Object[] { workspace, artifacts });
        Map<String, URL> artifactUrls = new HashMap<>();
        BlobStore blobStore = getContext().getBlobStore();

        // Map artifacts to urls for upload
        for (Map.Entry<String, String> entry : artifacts.entrySet()) {
            String path = "artifacts/" + entry.getKey();
            String blobPath = getBlobPath(path);
            Blob blob = blobStore.blobBuilder(blobPath).build();
            blob.getMetadata().setContainer(provider.getContainer());
            artifactUrls.put(entry.getValue(), provider.toExternalURL(blob, HttpMethod.PUT));
        }

        workspace.act(new UploadToBlobStorage(artifactUrls, listener));
        listener.getLogger().printf("Uploaded %s artifact(s) to %s%n", artifactUrls.size(), provider.toURI(provider.getContainer(), getBlobPath("artifacts/")));
    }

    private static class UploadToBlobStorage extends MasterToSlaveFileCallable<Void> {
        private static final long serialVersionUID = 1L;

        private final Map<String, URL> artifactUrls; // e.g. "target/x.war", "http://..."
        private final TaskListener listener;
        // Bind when constructed on the master side; on the agent side, deserialize those values.
        private final int stopAfterAttemptNumber = STOP_AFTER_ATTEMPT_NUMBER;
        private final long waitMultiplier = WAIT_MULTIPLIER;
        private final long waitMaximum = WAIT_MAXIMUM;
        private final long timeout = TIMEOUT;

        UploadToBlobStorage(Map<String, URL> artifactUrls, TaskListener listener) {
            this.artifactUrls = artifactUrls;
            this.listener = listener;
        }

        @Override
        public Void invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
            for (Map.Entry<String, URL> entry : artifactUrls.entrySet()) {
                Path local = f.toPath().resolve(entry.getKey());
                URL url = entry.getValue();
                uploadFile(local, url, listener, stopAfterAttemptNumber, waitMultiplier, waitMaximum, timeout);
            }
            return null;
        }
    }

    @Override
    public boolean delete() throws IOException, InterruptedException {
        String blobPath = getBlobPath("");
        if (!provider.isDeleteBlobs()) {
            LOGGER.log(Level.FINE, "Ignoring blob deletion: {0}", blobPath);
            return false;
        }
        return delete(provider, getContext().getBlobStore(), blobPath);
    }

    /**
     * Delete all blobs starting with prefix
     */
    public static boolean delete(BlobStoreProvider provider, BlobStore blobStore, String prefix) throws IOException, InterruptedException {
        try {
            Iterator<StorageMetadata> it = new JCloudsVirtualFile.PageSetIterable(blobStore, provider.getContainer(), ListContainerOptions.Builder.prefix(prefix).recursive());
            boolean found = false;
            while (it.hasNext()) {
                StorageMetadata sm = it.next();
                String path = sm.getName();
                assert path.startsWith(prefix);
                LOGGER.fine("deleting " + path);
                blobStore.removeBlob(provider.getContainer(), path);
                found = true;
            }
            return found;
        } catch (RuntimeException x) {
            throw new IOException(x);
        }
    }

    @Override
    public VirtualFile root() {
        return new JCloudsVirtualFile(provider, provider.getContainer(), getBlobPath("artifacts"));
    }

    @Override
    public void stash(String name, FilePath workspace, Launcher launcher, EnvVars env, TaskListener listener, String includes, String excludes, boolean useDefaultExcludes, boolean allowEmpty) throws IOException, InterruptedException {
        BlobStore blobStore = getContext().getBlobStore();

        // Map stash to url for upload
        String path = getBlobPath("stashes/" + name + ".tgz");
        Blob blob = blobStore.blobBuilder(path).build();
        blob.getMetadata().setContainer(provider.getContainer());
        URL url = provider.toExternalURL(blob, HttpMethod.PUT);
        int count = workspace.act(new Stash(url, includes, excludes, useDefaultExcludes, WorkspaceList.tempDir(workspace).getRemote(), listener));
        if (count == 0 && !allowEmpty) {
            throw new AbortException("No files included in stash");
        }
        listener.getLogger().printf("Stashed %d file(s) to %s%n", count, provider.toURI(provider.getContainer(), path));
    }

    private static final class Stash extends MasterToSlaveFileCallable<Integer> {
        private static final long serialVersionUID = 1L;
        private final URL url;
        private final String includes, excludes;
        private final boolean useDefaultExcludes;
        private final String tempDir;
        private final TaskListener listener;
        private final int stopAfterAttemptNumber = STOP_AFTER_ATTEMPT_NUMBER;
        private final long waitMultiplier = WAIT_MULTIPLIER;
        private final long waitMaximum = WAIT_MAXIMUM;
        private final long timeout = TIMEOUT;

        Stash(URL url, String includes, String excludes, boolean useDefaultExcludes, String tempDir, TaskListener listener) throws IOException {
            this.url = url;
            this.includes = includes;
            this.excludes = excludes;
            this.useDefaultExcludes = useDefaultExcludes;
            this.tempDir = tempDir;
            this.listener = listener;
        }

        @Override
        public Integer invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
            // TODO JCLOUDS-769 streaming upload is not currently straightforward, so using a temp file pending rewrite to use multipart uploads
            // (we prefer not to upload individual files for stashes, so as to preserve symlinks & file permissions, as StashManager’s default does)
            Path tempDirP = Paths.get(tempDir);
            Files.createDirectories(tempDirP);
            Path tmp = Files.createTempFile(tempDirP, "stash", ".tgz");
            try {
                int count;
                try (OutputStream os = Files.newOutputStream(tmp)) {
                    count = new FilePath(f).archive(ArchiverFactory.TARGZ, os, new DirScanner.Glob(Util.fixEmpty(includes) == null ? "**" : includes, excludes, useDefaultExcludes));
                } catch (InvalidPathException e) {
                    throw new IOException(e);
                }
                if (count > 0) {
                    uploadFile(tmp, url, listener, stopAfterAttemptNumber, waitMultiplier, waitMaximum, timeout);
                }
                return count;
            } finally {
                Files.delete(tmp);
            }
        }
    }

    @Override
    public void unstash(String name, FilePath workspace, Launcher launcher, EnvVars env, TaskListener listener) throws IOException, InterruptedException {
        BlobStore blobStore = getContext().getBlobStore();

        // Map stash to url for download
        String blobPath = getBlobPath("stashes/" + name + ".tgz");
        Blob blob = blobStore.getBlob(provider.getContainer(), blobPath);
        if (blob == null) {
            throw new AbortException(
                    String.format("No such saved stash ‘%s’ found at %s/%s", name, provider.getContainer(), blobPath));
        }
        URL url = provider.toExternalURL(blob, HttpMethod.GET);
        workspace.act(new Unstash(url, listener));
        listener.getLogger().printf("Unstashed file(s) from %s%n", provider.toURI(provider.getContainer(), blobPath));
    }

    private static final class Unstash extends MasterToSlaveFileCallable<Void> {
        private static final long serialVersionUID = 1L;
        private final URL url;
        private final TaskListener listener;
        private final int stopAfterAttemptNumber = STOP_AFTER_ATTEMPT_NUMBER;
        private final long waitMultiplier = WAIT_MULTIPLIER;
        private final long waitMaximum = WAIT_MAXIMUM;
        private final long timeout = TIMEOUT;

        Unstash(URL url, TaskListener listener) throws IOException {
            this.url = url;
            this.listener = listener;
        }

        @Override
        public Void invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
            connect(url, "download", urlSafe -> "download " + urlSafe + " into " + f, connection -> {}, connection -> {
                try (InputStream is = connection.getInputStream()) {
                    new FilePath(f).untarFrom(is, FilePath.TarCompression.GZIP);
                    // Note that this API currently offers no count of files in the tarball we could report.
                }
            }, listener, stopAfterAttemptNumber, waitMultiplier, waitMaximum, timeout);
            return null;
        }
    }

    @Override
    public void clearAllStashes(TaskListener listener) throws IOException, InterruptedException {
        String stashPrefix = getBlobPath("stashes/");

        if (!provider.isDeleteStashes()) {
            LOGGER.log(Level.FINE, "Ignoring stash deletion: {0}", stashPrefix);
            return;
        }

        BlobStore blobStore = getContext().getBlobStore();
        int count = 0;
        try {
            Iterator<StorageMetadata> it = new JCloudsVirtualFile.PageSetIterable(blobStore, provider.getContainer(), ListContainerOptions.Builder.prefix(stashPrefix).recursive());
            while (it.hasNext()) {
                StorageMetadata sm = it.next();
                String path = sm.getName();
                assert path.startsWith(stashPrefix);
                LOGGER.fine("deleting " + path);
                blobStore.removeBlob(provider.getContainer(), path);
                count++;
            }
        } catch (RuntimeException x) {
            throw new IOException(x);
        }
        listener.getLogger().printf("Deleted %d stash(es) from %s%n", count, provider.toURI(provider.getContainer(), stashPrefix));
    }

    @Override
    public void copyAllArtifactsAndStashes(Run<?, ?> to, TaskListener listener) throws IOException, InterruptedException {
        ArtifactManager am = to.pickArtifactManager();
        if (!(am instanceof JCloudsArtifactManager)) {
            throw new AbortException("Cannot copy artifacts and stashes to " + to + " using " + am.getClass().getName());
        }
        JCloudsArtifactManager dest = (JCloudsArtifactManager) am;
        String allPrefix = getBlobPath("");
        BlobStore blobStore = getContext().getBlobStore();
        int count = 0;
        try {
            Iterator<StorageMetadata> it = new JCloudsVirtualFile.PageSetIterable(blobStore, provider.getContainer(), ListContainerOptions.Builder.prefix(allPrefix).recursive());
            while (it.hasNext()) {
                StorageMetadata sm = it.next();
                String path = sm.getName();
                assert path.startsWith(allPrefix);
                String destPath = getBlobPath(dest.key, path.substring(allPrefix.length()));
                LOGGER.fine("copying " + path + " to " + destPath);
                blobStore.copyBlob(provider.getContainer(), path, provider.getContainer(), destPath, CopyOptions.NONE);
                count++;
            }
        } catch (RuntimeException x) {
            throw new IOException(x);
        }
        listener.getLogger().printf("Copied %d artifact(s)/stash(es) from %s to %s%n", count, provider.toURI(provider.getContainer(), allPrefix), provider.toURI(provider.getContainer(), dest.getBlobPath("")));
    }

    private BlobStoreContext getContext() throws IOException {
        return provider.getContext();
    }

    /**
     * Number of upload/download attempts of nonfatal errors before giving up.
     */
    static int STOP_AFTER_ATTEMPT_NUMBER = Integer.getInteger(JCloudsArtifactManager.class.getName() + ".STOP_AFTER_ATTEMPT_NUMBER", 10);
    /**
     * Initial number of milliseconds between first and second upload/download attempts.
     * Subsequent ones increase exponentially.
     * Note that this is not a <em>randomized</em> exponential backoff;
     * and the base of the exponent is hard-coded to 2.
     */
    @SuppressWarnings("FieldMayBeFinal")
    private static long WAIT_MULTIPLIER = Long.getLong(JCloudsArtifactManager.class.getName() + ".WAIT_MULTIPLIER", 100);
    /**
     * Maximum number of seconds between upload/download attempts.
     */
    @SuppressWarnings("FieldMayBeFinal")
    private static long WAIT_MAXIMUM = Long.getLong(JCloudsArtifactManager.class.getName() + ".WAIT_MAXIMUM", 300);
    /**
     * Number of seconds to permit a single upload/download attempt to take.
     */
    static long TIMEOUT = Long.getLong(JCloudsArtifactManager.class.getName() + ".TIMEOUT", /* 15m */15 * 60);

    private static final ExecutorService executors = JenkinsJVM.isJenkinsJVM() ? Computer.threadPoolForRemoting : Executors.newCachedThreadPool();
    
    @FunctionalInterface
    private interface ConnectionProcessor {
        void handle(HttpURLConnection connection) throws IOException, InterruptedException;
    }

    /**
     * Perform an HTTP network operation with appropriate timeouts and retries.
     * @param url a URL to connect to (any query string is considered secret and will be masked from logs)
     * @param whatConcise a short description of the operation, like {@code upload}
     * @param whatVerbose a longer description of the operation taking a sanitized URL, like {@code uploading … to …}
     * @param afterConnect what to do, if anything, after a connection has been established but before getting the server’s response
     * @param afterResponse what to do, if anything, after a successful (2xx) server response
     * @param listener a place to print messages
     * @param stopAfterAttemptNumber see {@link #STOP_AFTER_ATTEMPT_NUMBER}
     * @param waitMultiplier see {@link #WAIT_MULTIPLIER}
     * @param waitMaximum see {@link #WAIT_MAXIMUM}
     * @param timeout see {@link #TIMEOUT}
     * @throws IOException if there is an unrecoverable error; {@link AbortException} will be used where appropriate
     * @throws InterruptedException if the transfer is interrupted
     */
    private static void connect(URL url, String whatConcise, Function<String, String> whatVerbose, ConnectionProcessor afterConnect, ConnectionProcessor afterResponse, TaskListener listener, int stopAfterAttemptNumber, long waitMultiplier, long waitMaximum, long timeout) throws IOException, InterruptedException {
        AtomicInteger responseCode = new AtomicInteger();
        int attempt = 1;
        while (true) {
            try {
                try {
                    executors.submit(() -> {
                        responseCode.set(0);
                        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                        afterConnect.handle(connection);
                        responseCode.set(connection.getResponseCode());
                        if (responseCode.get() < 200 || responseCode.get() >= 300) {
                            String diag;
                            try (InputStream err = connection.getErrorStream()) {
                                diag = err != null ? IOUtils.toString(err, connection.getContentEncoding()) : null;
                            }
                            throw new AbortException(String.format("Failed to %s, response: %d %s, body: %s", whatVerbose.apply(url.toString().replaceFirst("[?].+$", "?…")), responseCode.get(), connection.getResponseMessage(), diag));
                        }
                        afterResponse.handle(connection);
                        return null; // success
                    }).get(timeout, TimeUnit.SECONDS);
                } catch (TimeoutException x) {
                    throw new ExecutionException(new IOException(x)); // ExecutionException unwrapped & treated as retryable below
                }
                listener.getLogger().flush(); // seems we can get interleaved output with master otherwise
                return; // success
            } catch (ExecutionException wrapped) {
                Throwable x = wrapped.getCause();
                if (x instanceof IOException) {
                    if (attempt == stopAfterAttemptNumber) {
                        throw (IOException) x; // last chance
                    }
                    if (responseCode.get() > 0 && responseCode.get() < 200 || responseCode.get() >= 300 && responseCode.get() < 500) {
                        throw (IOException) x; // 4xx errors should not be retried
                    }
                    // TODO exponent base (2) could be made into a configurable parameter
                    Thread.sleep(Math.min(((long) Math.pow(2, attempt)) * waitMultiplier, waitMaximum * 1000));
                    listener.getLogger().printf("Retrying %s after: %s%n", whatConcise, x instanceof AbortException ? x.getMessage() : x.toString());
                    attempt++; // and continue
                } else if (x instanceof InterruptedException) { // all other exceptions considered fatal
                    throw (InterruptedException) x;
                } else if (x instanceof RuntimeException) {
                    throw (RuntimeException) x;
                } else if (x != null) {
                    throw new RuntimeException(x);
                } else {
                    throw new IllegalStateException();
                }
            }
        }
    }

    /**
     * Upload a file to a URL
     */
    private static void uploadFile(Path f, URL url, TaskListener listener, int stopAfterAttemptNumber, long waitMultiplier, long waitMaximum, long timeout) throws IOException, InterruptedException {
        connect(url, "upload", urlSafe -> "upload " + f + " to " + urlSafe, connection -> {
            connection.setDoOutput(true);
            connection.setRequestMethod("PUT");
            connection.setFixedLengthStreamingMode(Files.size(f)); // prevent loading file in memory
            try (OutputStream out = connection.getOutputStream()) {
                Files.copy(f, out);
            }
        }, connection -> {}, listener, stopAfterAttemptNumber, waitMultiplier, waitMaximum, timeout);
    }

}
