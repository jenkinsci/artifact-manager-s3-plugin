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

import hudson.AbortException;
import hudson.EnvVars;
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
import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.artifact_manager_jclouds.BlobStoreProvider.HttpMethod;
import io.jenkins.plugins.httpclient.RobustHTTPClient;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.MasterToSlaveFileCallable;
import jenkins.model.ArtifactManager;
import jenkins.util.VirtualFile;
import org.apache.http.client.methods.HttpGet;
import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.BlobStores;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.blobstore.options.CopyOptions;
import org.jclouds.blobstore.options.ListContainerOptions;
import org.jenkinsci.plugins.workflow.flow.StashManager;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Jenkins artifact/stash implementation using any blob store supported by Apache jclouds.
 * To offer a new backend, implement {@link BlobStoreProvider}.
 */
@Restricted(NoExternalUse.class)
public final class JCloudsArtifactManager extends ArtifactManager implements StashManager.StashAwareArtifactManager {

    private static final Logger LOGGER = Logger.getLogger(JCloudsArtifactManager.class.getName());

    static RobustHTTPClient client = new RobustHTTPClient();

    private final BlobStoreProvider provider;

    private transient String key; // e.g. myorg/myrepo/master/123

    JCloudsArtifactManager(@NonNull  Run<?, ?> build, BlobStoreProvider provider) {
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
    public void onLoad(@NonNull Run<?, ?> build) {
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
        // Bind when constructed on the master side; on the agent side, deserialize the same configuration.
        private final RobustHTTPClient client = JCloudsArtifactManager.client;

        UploadToBlobStorage(Map<String, URL> artifactUrls, TaskListener listener) {
            this.artifactUrls = artifactUrls;
            this.listener = listener;
        }

        @Override
        public Void invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
            for (Map.Entry<String, URL> entry : artifactUrls.entrySet()) {
                client.uploadFile(new File(f, entry.getKey()), entry.getValue(), listener);
            }
            return null;
        }
    }

    @Override
    public boolean delete() throws IOException, InterruptedException {
        String blobPath = getBlobPath("");
        if (!provider.isDeleteArtifacts()) {
            LOGGER.log(Level.FINE, "Ignoring blob deletion: {0}", blobPath);
            return false;
        }
        return JCloudsVirtualFile.delete(provider, getContext().getBlobStore(), blobPath);
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
        workspace.act(new Stash(url, provider.toURI(provider.getContainer(), path), includes, excludes, useDefaultExcludes, allowEmpty, WorkspaceList.tempDir(workspace).getRemote(), listener));
    }

    private static final class Stash extends MasterToSlaveFileCallable<Void> {
        private static final long serialVersionUID = 1L;
        private final URL url;
        private final URI uri;
        private final String includes, excludes;
        private final boolean useDefaultExcludes;
        private final boolean allowEmpty;
        private final String tempDir;
        private final TaskListener listener;
        private final RobustHTTPClient client = JCloudsArtifactManager.client;

        Stash(URL url, URI uri, String includes, String excludes, boolean useDefaultExcludes, boolean allowEmpty, String tempDir, TaskListener listener) throws IOException {
            /** Actual destination as a presigned URL. */
            this.url = url;
            /** Logical location for display purposes only. */
            this.uri = uri;
            this.includes = includes;
            this.excludes = excludes;
            this.useDefaultExcludes = useDefaultExcludes;
            this.allowEmpty = allowEmpty;
            this.tempDir = tempDir;
            this.listener = listener;
        }

        @Override
        public Void invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
            // TODO use streaming upload rather than a temp file; is it necessary to set the content length in advance?
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
                if (count == 0 && !allowEmpty) {
                    throw new AbortException("No files included in stash");
                }
                client.uploadFile(tmp.toFile(), url, listener);
                listener.getLogger().printf("Stashed %d file(s) to %s%n", count, uri);
                return null;
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
        private final RobustHTTPClient client = JCloudsArtifactManager.client;

        Unstash(URL url, TaskListener listener) throws IOException {
            this.url = url;
            this.listener = listener;
        }

        @Override
        public Void invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
            client.connect("download", "download " + RobustHTTPClient.sanitize(url) + " into " + f, c -> c.execute(new HttpGet(url.toString())), response -> {
                try (InputStream is = response.getEntity().getContent()) {
                    new FilePath(f).untarFrom(is, FilePath.TarCompression.GZIP);
                    // Note that this API currently offers no count of files in the tarball we could report.
                }
            }, listener);
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
            for (StorageMetadata sm : BlobStores.listAll(blobStore, provider.getContainer(), ListContainerOptions.Builder.prefix(stashPrefix).recursive())) {
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
            for (StorageMetadata sm : BlobStores.listAll(blobStore, provider.getContainer(), ListContainerOptions.Builder.prefix(allPrefix).recursive())) {
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

}
