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

import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.net.URL;

import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.domain.Blob;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionPoint;
import hudson.model.AbstractDescribableImpl;

/**
 * Provider for jclouds-based blob stores usable for artifact storage.
 * An instance will be copied into a build record together with any fields it defines.
 */
@Restricted(Beta.class)
public abstract class BlobStoreProvider extends AbstractDescribableImpl<BlobStoreProvider> implements ExtensionPoint, Serializable {

    private static final long serialVersionUID = -861350249543443493L;

    public enum HttpMethod {
        GET, PUT;
    }

    /** A constant for the blob path prefix to use. */
    @NonNull
    public abstract String getPrefix();

    /** A constant for the blob container name to use. */
    @NonNull
    public abstract String getContainer();

    /** A constant to define whether we should delete artifacts or leave them to be managed on the blob service side. */
    public abstract boolean isDeleteArtifacts();

    /** A constant to define whether we should delete stashes or leave them to be managed on the blob service side. */
    public abstract boolean isDeleteStashes();

    /** Creates the jclouds handle for working with blob. */
    @NonNull
    public abstract BlobStoreContext getContext() throws IOException;

    /**
     * Get a provider-specific URI.
     * 
     * @param container
     *            container where this exists.
     * @param key
     *            fully qualified name relative to the container.
     * @return the URI
     */
    @NonNull
    public abstract URI toURI(@NonNull String container, @NonNull String key);

    /**
     * Generate a URL valid for downloading OR uploading the blob for a limited period of time
     * 
     * @param blob
     *            blob to generate the URL for
     * @param httpMethod
     *            HTTP method to create a URL for (downloads or uploads)
     * @return the URL
     * @throws IOException
     */
    @NonNull
    public abstract URL toExternalURL(@NonNull Blob blob, @NonNull HttpMethod httpMethod) throws IOException;

    @Override
    public BlobStoreProviderDescriptor getDescriptor() {
        return (BlobStoreProviderDescriptor) super.getDescriptor();
    }

}
