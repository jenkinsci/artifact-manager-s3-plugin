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

import com.github.rholder.retry.Attempt;
import com.github.rholder.retry.AttemptTimeLimiters;
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

import com.github.rholder.retry.RetryException;
import com.github.rholder.retry.RetryListener;
import com.github.rholder.retry.RetryerBuilder;
import com.github.rholder.retry.StopStrategies;
import com.github.rholder.retry.WaitStrategies;
import com.google.common.util.concurrent.UncheckedTimeoutException;
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
import java.util.concurrent.atomic.AtomicReference;
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
        private final int stopAfterAttemptNumber = UPLOAD_STOP_AFTER_ATTEMPT_NUMBER;
        private final long waitMultiplier = UPLOAD_WAIT_MULTIPLIER;
        private final long waitMaximum = UPLOAD_WAIT_MAXIMUM;
        private final long timeout = UPLOAD_TIMEOUT;

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
        private final int stopAfterAttemptNumber = UPLOAD_STOP_AFTER_ATTEMPT_NUMBER;
        private final long waitMultiplier = UPLOAD_WAIT_MULTIPLIER;
        private final long waitMaximum = UPLOAD_WAIT_MAXIMUM;
        private final long timeout = UPLOAD_TIMEOUT;

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
        workspace.act(new Unstash(url));
        listener.getLogger().printf("Unstashed file(s) from %s%n", provider.toURI(provider.getContainer(), blobPath));
    }

    private static final class Unstash extends MasterToSlaveFileCallable<Void> {
        private static final long serialVersionUID = 1L;
        private final URL url;

        Unstash(URL url) throws IOException {
            this.url = url;
        }

        @Override
        public Void invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
            try (InputStream is = url.openStream()) {
                new FilePath(f).untarFrom(is, FilePath.TarCompression.GZIP);
                // Note that this API currently offers no count of files in the tarball we could report.
            }
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

    private static final class HTTPAbortException extends AbortException {
        final int code;
        HTTPAbortException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    /**
     * Number of upload attempts of nonfatal errors before giving up.
     */
    static int UPLOAD_STOP_AFTER_ATTEMPT_NUMBER = Integer.getInteger(JCloudsArtifactManager.class.getName() + ".UPLOAD_STOP_AFTER_ATTEMPT_NUMBER", 10);
    /**
     * Initial number of milliseconds between first and second upload attempts.
     * Subsequent ones increase exponentially.
     * Note that this is not a <em>randomized</em> exponential backoff;
     * and the base of the exponent is hard-coded to 2.
     */
    static long UPLOAD_WAIT_MULTIPLIER = Long.getLong(JCloudsArtifactManager.class.getName() + ".UPLOAD_WAIT_MULTIPLIER", 100);
    /**
     * Maximum number of seconds between upload attempts.
     */
    static long UPLOAD_WAIT_MAXIMUM = Long.getLong(JCloudsArtifactManager.class.getName() + ".UPLOAD_WAIT_MAXIMUM", 300);
    /**
     * Number of seconds to permit a single upload attempt to take.
     */
    static long UPLOAD_TIMEOUT = Long.getLong(JCloudsArtifactManager.class.getName() + ".UPLOAD_TIMEOUT", /* 15m */15 * 60);

    private static final ExecutorService executors = JenkinsJVM.isJenkinsJVM() ? Computer.threadPoolForRemoting : Executors.newCachedThreadPool();

    /**
     * Upload a file to a URL
     */
    @SuppressWarnings("Convert2Lambda") // bogus use of generics (type variable should have been on class); cannot be made into a lambda
    private static void uploadFile(Path f, URL url, final TaskListener listener, int stopAfterAttemptNumber, long waitMultiplier, long waitMaximum, long timeout) throws IOException {
        String urlSafe = url.toString().replaceFirst("[?].+$", "?…");
        try {
            AtomicReference<Throwable> lastError = new AtomicReference<>();
            RetryerBuilder.<Void>newBuilder().
                    retryIfException(x -> x instanceof IOException && (!(x instanceof HTTPAbortException) || ((HTTPAbortException) x).code >= 500) || x instanceof UncheckedTimeoutException).
                    withRetryListener(new RetryListener() {
                        @Override
                        public <Void> void onRetry(Attempt<Void> attempt) {
                            if (attempt.hasException()) {
                                lastError.set(attempt.getExceptionCause());
                            }
                        }
                    }).
                    withStopStrategy(StopStrategies.stopAfterAttempt(stopAfterAttemptNumber)).
                    withWaitStrategy(WaitStrategies.exponentialWait(waitMultiplier, waitMaximum, TimeUnit.SECONDS)).
                    withAttemptTimeLimiter(AttemptTimeLimiters.fixedTimeLimit(timeout, TimeUnit.SECONDS, executors)).
                    build().call(() -> {
                Throwable t = lastError.get();
                if (t != null) {
                    listener.getLogger().println("Retrying upload after: " + (t instanceof AbortException ? t.getMessage() : t.toString()));
                }
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setDoOutput(true);
                connection.setRequestMethod("PUT");
                connection.setFixedLengthStreamingMode(Files.size(f)); // prevent loading file in memory
                try (OutputStream out = connection.getOutputStream()) {
                    Files.copy(f, out);
                }
                int responseCode = connection.getResponseCode();
                if (responseCode < 200 || responseCode >= 300) {
                    String diag;
                    try (InputStream err = connection.getErrorStream()) {
                        diag = err != null ? IOUtils.toString(err, connection.getContentEncoding()) : null;
                    }
                    throw new HTTPAbortException(responseCode, String.format("Failed to upload %s to %s, response: %d %s, body: %s", f.toAbsolutePath(), urlSafe, responseCode, connection.getResponseMessage(), diag));
                }
                return null;
            });
        } catch (ExecutionException | RetryException x) { // *sigh*, checked exceptions
            Throwable x2 = x.getCause();
            if (x2 instanceof IOException) {
                throw (IOException) x2;
            } else if (x2 instanceof RuntimeException) {
                throw (RuntimeException) x2;
            } else { // Error?
                throw new RuntimeException(x);
            }
        }
    }

}
