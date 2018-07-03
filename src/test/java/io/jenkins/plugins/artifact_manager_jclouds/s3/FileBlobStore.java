package io.jenkins.plugins.artifact_manager_jclouds.s3;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.artifact_manager_jclouds.BlobStoreProvider;
import org.jclouds.ContextBuilder;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.filesystem.reference.FilesystemConstants;
import org.jvnet.hudson.test.TemporaryDirectoryAllocator;
import jenkins.model.Jenkins;

/**
 * Fake BlobStoreProvider that stores empty files on disk.
 * The return of the toExternalURL is an URL  to the FileBlobStoreConfig#doExternalURL method.
 * toURI return the local path to the file created.
 */
class FileBlobStore extends BlobStoreProvider {

    private static final String PREFIX = "folder/";
    private static final String CONTAINER = "container";
    private final String baseFolder;
    private TemporaryDirectoryAllocator tempDirAllocator;

    public FileBlobStore() throws IOException {
        tempDirAllocator = new TemporaryDirectoryAllocator();
        baseFolder = tempDirAllocator.allocate().getAbsolutePath();
    }

    @NonNull
    @Override
    public String getPrefix() {
        return PREFIX;
    }

    @NonNull
    @Override
    public String getContainer() {
        return CONTAINER;
    }

    @Override
    public boolean isDeleteArtifacts() {
        return true;
    }

    @Override
    public boolean isDeleteStashes() {
        return true;
    }

    @NonNull
    @Override
    public BlobStoreContext getContext() {
        Properties prop = new Properties();
        prop.setProperty(FilesystemConstants.PROPERTY_BASEDIR, baseFolder);
        prop.setProperty(FilesystemConstants.PROPERTY_AUTO_DETECT_CONTENT_TYPE, "false");
        return ContextBuilder.newBuilder("filesystem").overrides(prop).build(BlobStoreContext.class);
    }

    @NonNull
    @Override
    public URI toURI(@NonNull String container, @NonNull String key) {
        return Paths.get(baseFolder, container, key).toUri();
    }

    @NonNull
    @Override
    public URL toExternalURL(@NonNull Blob blob, @NonNull HttpMethod httpMethod) throws IOException {
        String container = blob.getMetadata().getContainer();
        String name = blob.getMetadata().getName();

        if (HttpMethod.PUT.equals(httpMethod)) {
            Path path = Paths.get(baseFolder, container, name);
            Files.createDirectories(path.getParent());
            Files.createFile(path);
        }

        return new URL(Jenkins.get().getRootUrl() + "/descriptorByName/io.jenkins.plugins.artifact_manager_jclouds"
                       + ".s3.FileBlobStoreConfig/externalURL?container=" + container + "&fileName=" + name);
    }
}
