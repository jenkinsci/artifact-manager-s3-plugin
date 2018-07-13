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

import hudson.Extension;
import hudson.model.Run;
import jenkins.model.ArtifactManager;
import jenkins.model.ArtifactManagerFactory;
import jenkins.model.ArtifactManagerFactoryDescriptor;
import org.jenkinsci.Symbol;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Factory for {@link ArtifactManager}
 */
@Restricted(NoExternalUse.class)
public class JCloudsArtifactManagerFactory extends ArtifactManagerFactory {

    private final BlobStoreProvider provider;

    @DataBoundConstructor
    public JCloudsArtifactManagerFactory(BlobStoreProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException();
        }
        this.provider = provider;
    }

    private Object readResolve() {
        if (provider == null) {
            throw new IllegalStateException("Missing provider field");
        }
        return this;
    }

    public BlobStoreProvider getProvider() {
        return provider;
    }

    @Override
    public ArtifactManager managerFor(Run<?, ?> build) {
        return new JCloudsArtifactManager(build, provider);
    }

    @Symbol("jclouds")
    @Extension
    public static final class DescriptorImpl extends ArtifactManagerFactoryDescriptor {

        @Override
        public String getDisplayName() {
            return "Cloud Artifact Storage";
        }

    }

}
