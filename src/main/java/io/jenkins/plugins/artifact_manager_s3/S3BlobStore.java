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

import io.jenkins.plugins.artifact_manager_jclouds.BlobStoreProvider;
import io.jenkins.plugins.artifact_manager_jclouds.BlobStoreProviderDescriptor;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.Date;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nonnull;

import org.apache.commons.lang.StringUtils;
import org.jclouds.ContextBuilder;
import org.jclouds.aws.domain.Region;
import org.jclouds.aws.domain.SessionCredentials;
import org.jclouds.aws.s3.AWSS3ProviderMetadata;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.domain.Credentials;
import org.jclouds.location.reference.LocationConstants;
import org.jclouds.osgi.ProviderRegistry;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSSessionCredentials;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.security.ACL;
import hudson.security.SecurityRealm;
import hudson.util.ListBoxModel;
import org.kohsuke.stapler.DataBoundSetter;
import shaded.com.google.common.base.Supplier;
import jenkins.model.Jenkins;
import com.cloudbees.jenkins.plugins.awscredentials.AmazonWebServicesCredentials;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;

/**
 * Extension that customizes JCloudsBlobStore for AWS S3. Credentials are fetched from the environment, env vars, aws
 * profiles,...
 */
@Restricted(NoExternalUse.class)
public class S3BlobStore extends BlobStoreProvider {

    private static final Logger LOGGER = Logger.getLogger(S3BlobStore.class.getName());

    private static final long serialVersionUID = -8864075675579867370L;
    public static final String PREFIX_PROPERTY = System.getProperty(S3BlobStore.class.getName() + ".prefix");
    public static final String CONTAINER_PROPERTY = System.getProperty(S3BlobStore.class.getName() + ".container");
    public static final String CREDENTIALS_ID_PROPERTY = System.getProperty(S3BlobStore.class.getName() + ".credentialsId");
    public static final String REGION_PROPERTY = System.getProperty(S3BlobStore.class.getName() + ".region");

    private String container = CONTAINER_PROPERTY;
    private String prefix = PREFIX_PROPERTY;
    private String credentialsId = CREDENTIALS_ID_PROPERTY;
    private String region = REGION_PROPERTY;

    @DataBoundConstructor
    public S3BlobStore() {}

    @Override
    public String getPrefix() {
        return prefix;
    }

    @Override
    public String getContainer() {
        return container;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public String getRegion() {
        return region;
    }

    @DataBoundSetter
    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    @DataBoundSetter
    public void setContainer(String container) {
        this.container = container;
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = credentialsId;
    }

    @DataBoundSetter
    public void setRegion(String region) {
        this.region = region;
    }

    @Override
    public BlobStoreContext getContext() throws IOException {
        LOGGER.log(Level.FINEST, "Building context");
        ProviderRegistry.registerProvider(AWSS3ProviderMetadata.builder().build());
        try {
            Properties props = new Properties();

            if(StringUtils.isNotBlank(region)) {
                props.setProperty(LocationConstants.PROPERTY_REGIONS, region);
            }

            return ContextBuilder.newBuilder("aws-s3").credentialsSupplier(getCredentialsSupplier())
                    .buildView(BlobStoreContext.class);
        } catch (NoSuchElementException x) {
            throw new IOException(x);
        }
    }

    static boolean BREAK_CREDS;

    private Supplier<Credentials> getCredentialsSupplier() throws IOException {
        // get user credentials from env vars, profiles,...
        AmazonS3ClientBuilder builder = AmazonS3ClientBuilder.standard();
        AWSCredentials awsCredentials = builder.getCredentials().getCredentials();
        if (awsCredentials == null) {
            throw new IOException("Unable to get credentials from environment");
        }

        // Assume we are using session credentials
        if(!(awsCredentials instanceof AWSSessionCredentials)){
            throw new IOException("No valid session credentials");
        }

        String sessionToken = ((AWSSessionCredentials) awsCredentials).getSessionToken();
        if (BREAK_CREDS) {
            sessionToken = "<broken>";
        }

        SessionCredentials sessionCredentials = SessionCredentials.builder()
                .accessKeyId(awsCredentials.getAWSAccessKeyId()) //
                .secretAccessKey(awsCredentials.getAWSSecretKey()) //
                .sessionToken(sessionToken) //
                .build();

        return () -> sessionCredentials;
    }

    @Nonnull
    @Override
    public URI toURI(@NonNull String container, @NonNull String key) {
        assert container != null;
        assert key != null;
        try {
            // TODO proper encoding
            return new URI(String.format("https://%s.s3.amazonaws.com/%s", container,
                    URLEncoder.encode(key, "UTF-8").replaceAll("%2F", "/").replaceAll("%3A", ":")));
        } catch (URISyntaxException | UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/dev/ShareObjectPreSignedURLJavaSDK.html">Generate a
     *      Pre-signed Object URL using AWS SDK for Java</a>
     */
    @Override
    public URL toExternalURL(@NonNull Blob blob, @NonNull HttpMethod httpMethod) throws IOException {
        assert blob != null;
        assert httpMethod != null;
        AmazonS3ClientBuilder builder = AmazonS3ClientBuilder.standard();
        Date expiration = new Date(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1));
        String container = blob.getMetadata().getContainer();
        String name = blob.getMetadata().getName();
        LOGGER.log(Level.FINE, "Generating presigned URL for {0} / {1} for method {2}",
                new Object[] { container, name, httpMethod });
        com.amazonaws.HttpMethod awsMethod;
        switch (httpMethod) {
        case PUT:
            awsMethod = com.amazonaws.HttpMethod.PUT;
            break;
        case GET:
            awsMethod = com.amazonaws.HttpMethod.GET;
            break;
        default:
            throw new IOException("HTTP Method " + httpMethod + " not supported for S3");
        }
        return builder.build().generatePresignedUrl(container, name, expiration, awsMethod);
    }

    @Extension
    public static final class DescriptorImpl extends BlobStoreProviderDescriptor {

        public DescriptorImpl() {
            super();
        }

        public ListBoxModel doFillCredentialsIdItems() {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            ListBoxModel credentials = new ListBoxModel();
            credentials.add("None", null);
            credentials.addAll(CredentialsProvider.listCredentials(AmazonWebServicesCredentials.class, Jenkins.get(),
                                                                   ACL.SYSTEM, Collections.emptyList() ,
                                                                   CredentialsMatchers.always()));
            return credentials;
        }

        public ListBoxModel doFillRegionItems() {
            ListBoxModel regions = new ListBoxModel();
            regions.add("Auto",null);
            for (String s : Region.DEFAULT_S3) {
                regions.add(s);
            }
            return regions;
        }

        public String getPrefix() {
            return System.getProperty(S3BlobStore.class.getName() + ".prefix");
        }

        public String getContainer() {
            return CONTAINER_PROPERTY;
        }

        public String getCredentialsId() {
            return CREDENTIALS_ID_PROPERTY;
        }

        public String getRegion() {
            return REGION_PROPERTY;
        }

        @Override
        public String getDisplayName() {
            return "Amazon S3";
        }

    }

}
