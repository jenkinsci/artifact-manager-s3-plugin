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

import java.util.logging.Logger;
import javax.annotation.Nonnull;
import net.sf.json.JSONObject;
import org.jclouds.aws.domain.Region;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.util.ListBoxModel;
import jenkins.model.GlobalConfiguration;

/**
 * Store the S3BlodStore configuration to save it on a separate file. This make that
 * the change of container does not affected to the Artifactory functionality, you could change the container
 * and it would still work if both container contains the same data.
 */
@Extension
public class S3BlobStoreConfig extends GlobalConfiguration {
    private static final Logger LOGGER = Logger.getLogger(S3BlobStoreConfig.class.getName());

    @SuppressWarnings("FieldMayBeFinal")
    private static boolean DELETE_BLOBS = Boolean.getBoolean(S3BlobStoreConfig.class.getName() + ".deleteBlobs");
    @SuppressWarnings("FieldMayBeFinal")
    private static boolean DELETE_STASHES = Boolean.getBoolean(S3BlobStoreConfig.class.getName() + ".deleteStashes");

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

    @DataBoundConstructor
    public S3BlobStoreConfig() {
        load();
    }

    public String getContainer() {
        return container;
    }

    @DataBoundSetter
    public void setContainer(String container) {
        this.container = container;
    }

    public String getPrefix() {
        return prefix;
    }

    @DataBoundSetter
    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public String getRegion() {
        return region;
    }
    @DataBoundSetter
    public void setRegion(String region) {
        this.region = region;
    }

    public boolean isDeleteBlobs() {
        return DELETE_BLOBS;
    }

    public boolean isDeleteStashes() {
        return DELETE_STASHES;
    }

    @Nonnull
    @Override
    public String getDisplayName() {
        return "Amazon S3 Bucket Access settings";
    }

    @Override
    public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
        super.configure(req, json);
        save();
        return true;
    }

    @Nonnull
    public static S3BlobStoreConfig get() {
        return ExtensionList.lookupSingleton(S3BlobStoreConfig.class);
    }

    public ListBoxModel doFillRegionItems() {
        ListBoxModel regions = new ListBoxModel();
        regions.add("Auto", "");
        for (String s : Region.DEFAULT_S3) {
            regions.add(s);
        }
        return regions;
    }
}