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
import java.util.logging.Level;
import java.util.logging.Logger;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.artifact_manager_jclouds.BlobStoreProvider;
import io.jenkins.plugins.artifact_manager_jclouds.JCloudsArtifactManagerFactory;
import hudson.Extension;
import hudson.XmlFile;
import hudson.model.Saveable;
import hudson.model.listeners.SaveableListener;
import jenkins.model.ArtifactManagerConfiguration;
import jenkins.model.ArtifactManagerFactory;
import jenkins.model.Jenkins;

/**
 * Store the S3BlodStore configuration in a transient object to save it on a separate file. This make that
 * the change of container does not affected to the Artifactory functionality, you could change the container
 * and it would still work if both container contains the same data.
 */
class S3BlobStoreConfig {
    private static final Logger LOGGER = Logger.getLogger(S3BlobStore.class.getName());

    /**
     * Name of the S3 Bucket.
     */
    private String container;
    /**
     * Prefix to use for files, use to be a folder.
     */
    private String prefix;
    /**
     * force the region to use for the URLs generated.
     */
    private String region;

    public S3BlobStoreConfig() {
        load();
    }

    public String getContainer() {
        return container;
    }

    public void setContainer(String container) {
        this.container = container;
    }

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    /**
     * load the S3BlodStore configuration from disk.
     */
    public synchronized void load() {
        XmlFile file = getConfigFile();
        if (!file.exists())
            return;

        try {
            file.unmarshal(this);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to load " + file, e);
        }
    }

    /**
     * save the S3BlodStore configuration to disk.
     */
    public synchronized void save() {
        try {
            getConfigFile().write(this);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to save " + getConfigFile(), e);
        }
    }

    /**
     *
     * @return the name of the file to save JENKINS_HOME/io.jenkins.plugins.artifact_manager_s3.S3BlobStoreConfig.xml
     */
    @NonNull
    private XmlFile getConfigFile() {
        return new XmlFile(new File(Jenkins.get().getRootDir(), S3BlobStoreConfig.class.getName() + ".xml"));
    }

    /**
     * SaveListener for the "Artifact Management for Builds" configuration, If S3BlodStore is configured it save the
     * configuration on a XML file.
     */
    @Extension
    public static final class SaveListenerS3Config extends SaveableListener {

        @Override
        public void onChange(Saveable o, XmlFile file) {
            if (o instanceof ArtifactManagerConfiguration) {
                ArtifactManagerConfiguration artifactManagerConfig = (ArtifactManagerConfiguration) o;
                for (ArtifactManagerFactory item : artifactManagerConfig.getArtifactManagerFactories()) {
                    if (isS3BlodStoreConfigured(item)) {
                        BlobStoreProvider provider = ((JCloudsArtifactManagerFactory) item).getProvider();
                        S3BlobStore.S3BlodStoreDescriptor descriptor = (S3BlobStore.S3BlodStoreDescriptor) provider
                                .getDescriptor();
                        descriptor.getConfiguration().save();
                    }
                }
            }
        }

        private boolean isS3BlodStoreConfigured(ArtifactManagerFactory item) {
            return item instanceof JCloudsArtifactManagerFactory && ((JCloudsArtifactManagerFactory) item)
                    .getProvider() instanceof S3BlobStore;
        }
    }
}