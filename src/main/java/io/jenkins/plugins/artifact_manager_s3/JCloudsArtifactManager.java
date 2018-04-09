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
import java.util.NoSuchElementException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jclouds.ContextBuilder;
import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.blobstore.options.CopyOptions;
import org.jclouds.blobstore.options.ListContainerOptions;
import org.jclouds.domain.Credentials;
import org.jclouds.osgi.ProviderRegistry;
import org.jenkinsci.plugins.workflow.flow.StashManager;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.ExtensionList;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.BuildListener;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.slaves.WorkspaceList;
import hudson.util.DirScanner;
import hudson.util.io.ArchiverFactory;
import io.jenkins.plugins.artifact_manager_s3.JCloudsApiExtensionPoint.HttpMethod;
import jenkins.MasterToSlaveFileCallable;
import jenkins.model.ArtifactManager;
import jenkins.util.VirtualFile;
import shaded.com.google.common.base.Supplier;

/**
 * Artifact manager that stores files in a JClouds BlobStore using any of JClouds supported backends
 */
class JCloudsArtifactManager extends ArtifactManager implements StashManager.StashAwareArtifactManager {

    private static final Logger LOGGER = Logger.getLogger(JCloudsArtifactManager.class.getName());

    private static final String PROVIDER = System.getProperty("jclouds.provider", "aws-s3");

    private static final String BLOB_CONTAINER = System.getenv("S3_BUCKET");
    private static final String PREFIX = System.getenv("S3_DIR");

    private transient String key; // e.g. myorg/myrepo/master#123

    private transient String prefix;

    JCloudsArtifactManager(Run<?, ?> build) {
        onLoad(build);
    }

    @Override
    public void onLoad(Run<?, ?> build) {
        this.key = build.getExternalizableId();
    }

    // testing only
    String getPrefix() {
        return prefix == null ? PREFIX : prefix;
    }

    // testing only
    void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    private String getBlobPath(String s3path) {
        return getBlobPath(key, s3path);
    }

    private String getBlobPath(String key, String s3path) {
        return String.format("%s%s/%s", getPrefix(), key, s3path);
    }

    /*
     * This could be called multiple times
     */
    @Override
    public void archive(FilePath workspace, Launcher launcher, BuildListener listener, Map<String, String> artifacts)
            throws IOException, InterruptedException {
        LOGGER.log(Level.FINE, "Archiving from {0}: {1}", new Object[] { workspace, artifacts });
        Map<String, URL> artifactUrls = new HashMap<>();
        BlobStore blobStore = getContext(getExtension(PROVIDER).getCredentialsSupplier())
                .getBlobStore();
        JCloudsApiExtensionPoint extension = getExtension(PROVIDER);

        // Map artifacts to urls for upload
        for (Map.Entry<String, String> entry : artifacts.entrySet()) {
            String s3path = "artifacts/" + entry.getKey();
            String blobPath = getBlobPath(s3path);
            Blob blob = blobStore.blobBuilder(blobPath).build();
            blob.getMetadata().setContainer(BLOB_CONTAINER);
            artifactUrls.put(entry.getValue(), extension.toExternalURL(blob, HttpMethod.PUT));
        }

        workspace.act(new UploadToBlobStorage(listener, artifactUrls));
    }

    @Override
    public boolean delete() throws IOException, InterruptedException {
        BlobStore blobStore = getContext(getExtension(PROVIDER).getCredentialsSupplier()).getBlobStore();
        String prefix = getBlobPath("");
        Iterator<StorageMetadata> it = new JCloudsBlobStore.PageSetIterable(blobStore, BLOB_CONTAINER, ListContainerOptions.Builder.prefix(prefix).recursive());
        boolean found = false;
        while (it.hasNext()) {
            StorageMetadata sm = it.next();
            String path = sm.getName();
            assert path.startsWith(prefix);
            LOGGER.fine("deleting " + path);
            blobStore.removeBlob(BLOB_CONTAINER, path);
            found = true;
        }
        return found;
    }

    @Override
    public VirtualFile root() {
        return new JCloudsBlobStore(getExtension(PROVIDER), BLOB_CONTAINER, getBlobPath("artifacts"));
    }

    @Override
    public void stash(String name, FilePath workspace, Launcher launcher, EnvVars env, TaskListener listener, String includes, String excludes, boolean useDefaultExcludes, boolean allowEmpty) throws IOException, InterruptedException {
        BlobStore blobStore = getContext(getExtension(PROVIDER).getCredentialsSupplier())
                .getBlobStore();
        JCloudsApiExtensionPoint extension = getExtension(PROVIDER);

        // Map stash to url for upload
        Blob blob = blobStore.blobBuilder(getBlobPath("stashes/" + name + ".tgz")).build();
        blob.getMetadata().setContainer(BLOB_CONTAINER);
        URL url = extension.toExternalURL(blob, HttpMethod.PUT);
        workspace.act(new Stash(url, listener, includes, excludes, useDefaultExcludes, allowEmpty, WorkspaceList.tempDir(workspace).getRemote()));
    }

    private static final class Stash extends MasterToSlaveFileCallable<Void> {
        private static final long serialVersionUID = 1L;
        private final URL url;
        private final TaskListener listener;
        private final String includes, excludes;
        private final boolean useDefaultExcludes, allowEmpty;
        private final String tempDir;

        Stash(URL url, TaskListener listener, String includes, String excludes, boolean useDefaultExcludes, boolean allowEmpty, String tempDir) throws IOException {
            super();
            this.url = url;
            this.listener = listener;
            this.includes = includes;
            this.excludes = excludes;
            this.useDefaultExcludes = useDefaultExcludes;
            this.allowEmpty = allowEmpty;
            this.tempDir = tempDir;
        }

        @Override
        public Void invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
            // TODO JCLOUDS-769 streaming upload is not currently straightforward, so using a temp file pending rewrite to use multipart uploads
            // (we prefer not to upload individual files for stashes, so as to preserve symlinks & file permissions, as StashManager’s default does)
            Path tempDirP = Paths.get(tempDir);
            Files.createDirectories(tempDirP);
            Path tmp = Files.createTempFile(tempDirP, "stash", ".tgz");
            try {
                int count;
                try (OutputStream os = Files.newOutputStream(tmp)) {
                    count = new FilePath(f).archive(ArchiverFactory.TARGZ, os, new DirScanner.Glob(Util.fixEmpty(includes) == null ? "**" : includes, excludes, useDefaultExcludes));
                    if (count == 0 && !allowEmpty) {
                        throw new AbortException("No files included in stash");
                    }
                    listener.getLogger().println("Stashed " + count + " file(s)");
                } catch (InvalidPathException e) {
                    throw new IOException(e);
                }
                uploadFile(tmp, url);
            } finally {
                Files.delete(tmp);
            }
            return null;
        }
    }

    @Override
    public void unstash(String name, FilePath workspace, Launcher launcher, EnvVars env, TaskListener listener) throws IOException, InterruptedException {
        BlobStore blobStore = getContext(getExtension(PROVIDER).getCredentialsSupplier())
                .getBlobStore();
        JCloudsApiExtensionPoint extension = getExtension(PROVIDER);

        // Map stash to url for download
        String blobPath = getBlobPath("stashes/" + name + ".tgz");
        Blob blob = blobStore.getBlob(BLOB_CONTAINER, blobPath);
        if (blob == null) {
            throw new AbortException(
                    String.format("No such saved stash ‘%s’ found at %s/%s", name, BLOB_CONTAINER, blobPath));
        }
        URL url = extension.toExternalURL(blob, HttpMethod.GET);
        workspace.act(new Unstash(url, listener));
    }

    private static final class Unstash extends MasterToSlaveFileCallable<Void> {
        private static final long serialVersionUID = 1L;
        private final TaskListener listener;
        private final URL url;

        Unstash(URL url, TaskListener listener) throws IOException {
            super();
            this.listener = listener;
            this.url = url;
        }

        @Override
        public Void invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
            try (InputStream is = url.openStream()) {
                new FilePath(f).untarFrom(is, FilePath.TarCompression.GZIP);
            }
            // TODO print some summary to listener
            return null;
        }
    }

    @Override
    public void clearAllStashes(TaskListener listener) throws IOException, InterruptedException {
        String prefix = getBlobPath("stashes/");
        BlobStore blobStore = getContext(getExtension(PROVIDER).getCredentialsSupplier()).getBlobStore();
        Iterator<StorageMetadata> it = new JCloudsBlobStore.PageSetIterable(blobStore, BLOB_CONTAINER, ListContainerOptions.Builder.prefix(prefix).recursive());
        while (it.hasNext()) {
            StorageMetadata sm = it.next();
            String path = sm.getName();
            assert path.startsWith(prefix);
            LOGGER.fine("deleting " + path);
            blobStore.removeBlob(BLOB_CONTAINER, path);
        }
        // TODO print some summary to listener
    }

    @Override
    public void copyAllArtifactsAndStashes(Run<?, ?> to, TaskListener listener) throws IOException, InterruptedException {
        ArtifactManager am = to.pickArtifactManager();
        if (!(am instanceof JCloudsArtifactManager)) {
            throw new AbortException("Cannot copy artifacts and stashes to " + to + " using " + am.getClass().getName());
        }
        JCloudsArtifactManager dest = (JCloudsArtifactManager) am;
        String prefix = getBlobPath("");
        BlobStore blobStore = getContext(getExtension(PROVIDER).getCredentialsSupplier()).getBlobStore();
        Iterator<StorageMetadata> it = new JCloudsBlobStore.PageSetIterable(blobStore, BLOB_CONTAINER, ListContainerOptions.Builder.prefix(prefix).recursive());
        while (it.hasNext()) {
            StorageMetadata sm = it.next();
            String path = sm.getName();
            assert path.startsWith(prefix);
            String destPath = getBlobPath(dest.key, path.substring(prefix.length()));
            LOGGER.fine("copying " + path + " to " + destPath);
            blobStore.copyBlob(BLOB_CONTAINER, path, BLOB_CONTAINER, destPath, CopyOptions.NONE);
        }
        // TODO print some summary to listener
    }

    /**
     * Get the extension implementation for the specific JClouds provider or api id
     * 
     * @param providerOrApi
     * @throws IllegalStateException
     *             if extension is not present or run from the agent
     * @return the extension implementation
     */
    @NonNull
    private static JCloudsApiExtensionPoint getExtension(String providerOrApi) {
        return ExtensionList.lookup(JCloudsApiExtensionPoint.class).stream().filter(e -> providerOrApi.equals(e.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Could not find an extension for " + providerOrApi));
    }

    private static BlobStoreContext getContext(Supplier<Credentials> credentialsSupplier) throws IOException {
        try {
            // for some reason it won't find it at runtime otherwise
            ProviderRegistry.registerProvider(getExtension(PROVIDER).getProvider());

            return ContextBuilder.newBuilder(PROVIDER)
                    .credentialsSupplier(credentialsSupplier)
                    .buildView(BlobStoreContext.class);
        } catch (NoSuchElementException x) {
            throw new IOException(x);
        }
    }

    private static class UploadToBlobStorage extends MasterToSlaveFileCallable<Void> {
        private static final long serialVersionUID = 1L;

        private final BuildListener listener;
        private final Map<String, URL> artifactUrls; // e.g. "target/x.war", "http://..."

        public UploadToBlobStorage(BuildListener listener, Map<String, URL> artifactUrls) {
            super();
            this.listener = listener;
            this.artifactUrls = artifactUrls;
        }

        @Override
        public Void invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
            for (Map.Entry<String, URL> entry : artifactUrls.entrySet()) {
                Path local = f.toPath().resolve(entry.getKey());
                URL url = entry.getValue();
                LOGGER.log(Level.FINE, "Uploading {0} to {1}",
                        new String[] { local.toAbsolutePath().toString(), url.toString() });
                uploadFile(local, url);
            }
            return null;
        }
    }

    /**
     * Upload a file to a URL
     */
    private static void uploadFile(Path f, URL url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setDoOutput(true);
        connection.setRequestMethod("PUT");
        try (OutputStream out = connection.getOutputStream()) {
            Files.copy(f, out);
        }
        int responseCode = connection.getResponseCode();
        if (responseCode < 200 || responseCode >= 300) {
            throw new IOException(String.format("Failed to upload %s to %s, response: %d %s", f.toAbsolutePath(), url,
                    responseCode, connection.getResponseMessage()));
        }
        LOGGER.log(Level.FINE, "Uploaded {0} to {1}: {2}", new Object[] { f.toAbsolutePath(), url, responseCode });
    }
}
