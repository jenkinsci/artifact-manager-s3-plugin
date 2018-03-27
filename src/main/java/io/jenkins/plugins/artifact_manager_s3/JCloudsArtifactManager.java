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

import com.amazonaws.auth.AWSSessionCredentials;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
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
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.MasterToSlaveFileCallable;
import jenkins.model.ArtifactManager;
import jenkins.util.VirtualFile;
import org.jclouds.ContextBuilder;
import org.jclouds.aws.domain.SessionCredentials;
import org.jclouds.aws.s3.AWSS3ProviderMetadata;
import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.blobstore.options.CopyOptions;
import org.jclouds.blobstore.options.ListContainerOptions;
import org.jclouds.domain.Credentials;
import org.jclouds.osgi.ProviderRegistry;
import org.jenkinsci.plugins.workflow.flow.StashManager;
import shaded.com.google.common.base.Supplier;

/**
 * Artifact manager that stores files in a JClouds BlobStore using any of JClouds supported backends
 */
public class JCloudsArtifactManager extends ArtifactManager implements StashManager.StashAwareArtifactManager {

    private static final Logger LOGGER = Logger.getLogger(JCloudsArtifactManager.class.getName());

    private static final String PROVIDER = System.getProperty("jclouds.provider", "aws-s3");

    private static final String BLOB_CONTAINER = System.getenv("S3_BUCKET");
    private static final String PREFIX = System.getenv("S3_DIR");

    private transient String key; // e.g. myorg/myrepo/master#123
    private String id; // serialized

    JCloudsArtifactManager(Run<?, ?> build) {
        onLoad(build);
        id = UUID.randomUUID().toString();
    }

    @Override
    public void onLoad(Run<?, ?> build) {
        this.key = build.getExternalizableId();
    }

    private static String getBlobPath(String prefix, String key, String id) {
        return String.format("%s%s/%s", prefix, key, id);
    }

    private static String getBlobPath(String s3path, String prefix, String key, String id) {
        return String.format("%s/%s", getBlobPath(prefix, key, id), s3path);
    }

    /*
     * This could be called multiple times
     */
    @Override
    public void archive(FilePath workspace, Launcher launcher, BuildListener listener, Map<String, String> artifacts)
            throws IOException, InterruptedException {
        LOGGER.log(Level.FINE, "Archiving from {0}: {1}", new Object[] { workspace, artifacts });
        workspace.act(new UploadToBlobStorage(this, listener, artifacts));
    }

    @Override
    public boolean delete() throws IOException, InterruptedException {
        BlobStore blobStore = getContext(BLOB_CONTAINER).getBlobStore();
        String prefix = getBlobPath("", PREFIX, key, id);
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
        return new JCloudsBlobStore(getExtension(PROVIDER), BLOB_CONTAINER, getBlobPath("artifacts", PREFIX, key, id));
    }

    @Override
    public void stash(String name, FilePath workspace, Launcher launcher, EnvVars env, TaskListener listener, String includes, String excludes, boolean useDefaultExcludes, boolean allowEmpty) throws IOException, InterruptedException {
        workspace.act(new Stash(this, name, listener, includes, excludes, useDefaultExcludes, allowEmpty, WorkspaceList.tempDir(workspace).getRemote()));
    }
    private static final class Stash extends BlobCallable {
        private static final long serialVersionUID = 1L;
        private final String name;
        private final TaskListener listener;
        private final String includes, excludes;
        private final boolean useDefaultExcludes, allowEmpty;
        private final String tempDir;
        Stash(JCloudsArtifactManager artifactManager, String name, TaskListener listener, String includes, String excludes, boolean useDefaultExcludes, boolean allowEmpty, String tempDir) {
            super(artifactManager);
            this.name = name;
            this.listener = listener;
            this.includes = includes;
            this.excludes = excludes;
            this.useDefaultExcludes = useDefaultExcludes;
            this.allowEmpty = allowEmpty;
            this.tempDir = tempDir;
        }
        @Override
        protected void run(File f, BlobStore blobStore) throws IOException, InterruptedException {
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
                blobStore.putBlob(blobContainer, blobStore.blobBuilder(getBlobPath("stashes/" + name + ".tgz")).payload(tmp.toFile()).build());
            } finally {
                Files.delete(tmp);
            }
        }
    }

    @Override
    public void unstash(String name, FilePath workspace, Launcher launcher, EnvVars env, TaskListener listener) throws IOException, InterruptedException {
        workspace.act(new Unstash(this, name, listener));
    }
    private static final class Unstash extends BlobCallable {
        private static final long serialVersionUID = 1L;
        private final String name;
        private final TaskListener listener;
        Unstash(JCloudsArtifactManager artifactManager, String name, TaskListener listener) {
            super(artifactManager);
            this.name = name;
            this.listener = listener;
        }
        @Override
        protected void run(File f, BlobStore blobStore) throws IOException, InterruptedException {
            Blob blob = blobStore.getBlob(blobContainer, getBlobPath("stashes/" + name + ".tgz"));
            if (blob == null) {
                throw new AbortException("No such saved stash ‘" + name + "’");
            }
            try (InputStream is = blob.getPayload().openStream()) {
                new FilePath(f).untarFrom(is, FilePath.TarCompression.GZIP);
            }
            // TODO print some summary to listener
        }
    }

    @Override
    public void clearAllStashes(TaskListener listener) throws IOException, InterruptedException {
        String prefix = getBlobPath("stashes/", PREFIX, key, id);
        BlobStore blobStore = getContext(BLOB_CONTAINER).getBlobStore();
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
        String prefix = getBlobPath("", PREFIX, key, id);
        BlobStore blobStore = getContext(BLOB_CONTAINER).getBlobStore();
        Iterator<StorageMetadata> it = new JCloudsBlobStore.PageSetIterable(blobStore, BLOB_CONTAINER, ListContainerOptions.Builder.prefix(prefix).recursive());
        while (it.hasNext()) {
            StorageMetadata sm = it.next();
            String path = sm.getName();
            assert path.startsWith(prefix);
            String destPath = getBlobPath(path.substring(prefix.length()), PREFIX, dest.key, dest.id);
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

    private static BlobStoreContext getContext(String blobContainer) throws IOException {
        // TODO allow configuration

        // get user credentials from env vars, profiles,...
        AmazonS3ClientBuilder builder = AmazonS3ClientBuilder.standard();
        try {
            builder.build().doesBucketExistV2(blobContainer);
        } catch (RuntimeException x) {
            throw new IOException(x);
        }
        // Assume we are using session credentials
        AWSSessionCredentials awsCredentials = (AWSSessionCredentials) builder.getCredentials().getCredentials();
        if (awsCredentials == null) {
            throw new IOException("Unable to detect AWS session credentials");
        }

        SessionCredentials sessionCredentials = SessionCredentials.builder()
                .accessKeyId(awsCredentials.getAWSAccessKeyId()) //
                .secretAccessKey(awsCredentials.getAWSSecretKey()) //
                .sessionToken(awsCredentials.getSessionToken()) //
                .build();

        Supplier<Credentials> credentialsSupplier = new Supplier<Credentials>() {
            @Override
            public Credentials get() {
                return sessionCredentials;
            }
        };
        ProviderRegistry.registerProvider(AWSS3ProviderMetadata.builder().build());
        try {
            return ContextBuilder.newBuilder("aws-s3").credentialsSupplier(credentialsSupplier)
                .buildView(BlobStoreContext.class);
        } catch (NoSuchElementException x) {
            throw new IOException(x);
        }
    }

    private static abstract class BlobCallable extends MasterToSlaveFileCallable<Void> {

        private static final long serialVersionUID = 1L;
        protected final String blobContainer;
        private final String prefix, key, id;

        protected BlobCallable(JCloudsArtifactManager artifactManager) {
            blobContainer = BLOB_CONTAINER;
            prefix = PREFIX;
            key = artifactManager.key;
            id = artifactManager.id;
        }

        @Override
        public Void invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
            run(f, getContext(blobContainer).getBlobStore());
            return null;
        }

        protected final String getBlobPath(String s3path) {
            return JCloudsArtifactManager.getBlobPath(s3path, prefix, key, id);
        }

        protected abstract void run(File f, BlobStore blobStore) throws IOException, InterruptedException;

    }

    private static class UploadToBlobStorage extends BlobCallable {
        private static final long serialVersionUID = 1L;

        private BuildListener listener;
        private Map<String, String> artifacts; // e.g. "x.war", "target/x.war"

        public UploadToBlobStorage(JCloudsArtifactManager artifactManager, BuildListener listener,
                Map<String, String> artifacts) {
            super(artifactManager);
            this.listener = listener;
            this.artifacts = artifacts;
        }

        @Override
        public void run(File f, BlobStore blobStore) throws IOException, InterruptedException {
            for (Map.Entry<String, String> entry : artifacts.entrySet()) {
                File local = new File(f, entry.getValue());
                String s3path = "artifacts/" + entry.getKey();
                String blobPath = getBlobPath(s3path);
                // blob.upload(new FileInputStream(local)).withMetadata("PATH=" + s3path, "BUILD=" + key());
                LOGGER.log(Level.FINE, "Uploading {0} to {1} {2}",
                        new String[] { local.getAbsolutePath(), blobContainer, blobPath });
                Blob blob = blobStore.blobBuilder(blobPath).payload(local).build();
                blobStore.putBlob(blobContainer, blob);
            }
        }
    }

}
