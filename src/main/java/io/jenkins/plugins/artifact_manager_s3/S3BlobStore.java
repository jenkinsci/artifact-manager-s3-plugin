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
import java.util.Date;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

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
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import shaded.com.google.common.base.Supplier;

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
    public static final String REGION_PROPERTY = System.getProperty(S3BlobStore.class.getName() + ".region");

    private String container;
    private String prefix;
    private String region;

    @DataBoundConstructor
    public S3BlobStore() {
    }

    @Override
    public String getPrefix() {
        return StringUtils.defaultIfBlank(prefix, PREFIX_PROPERTY);
    }

    @Override
    public String getContainer() {
        return StringUtils.defaultIfBlank(container, CONTAINER_PROPERTY);
    }

    public String getRegion() {
        return StringUtils.defaultIfBlank(region, REGION_PROPERTY);
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
    public void setRegion(String region) {
        this.region = region;
    }

    @Override
    public BlobStoreContext getContext() throws IOException {
        LOGGER.log(Level.FINEST, "Building context");
        ProviderRegistry.registerProvider(AWSS3ProviderMetadata.builder().build());
        try {
            Properties props = new Properties();

            if(StringUtils.isNotBlank(getRegion())) {
                props.setProperty(LocationConstants.PROPERTY_REGIONS, getRegion());
            }

            return ContextBuilder.newBuilder("aws-s3").credentialsSupplier(getCredentialsSupplier())
                    .overrides(props)
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
        private static final String BUCKET_REGEXP = "^([a-z]|(\\d(?!\\d{0,2}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})))([a-z\\d]|(\\.(?!(\\.|-)))|(-(?!\\.))){1,61}[a-z\\d\\.]$";
        private static final Pattern bucketPattern = Pattern.compile(BUCKET_REGEXP);

        public DescriptorImpl() {
            super();
        }

        public ListBoxModel doFillRegionItems() {
            ListBoxModel regions = new ListBoxModel();
            regions.add("Auto","");
            for (String s : Region.DEFAULT_S3) {
                regions.add(s);
            }
            return regions;
        }

        public FormValidation doCheckContainer(@QueryParameter String container){
            FormValidation ret = FormValidation.ok();
            if(StringUtils.isBlank(container)){
                ret = FormValidation.error("The container name cannot be empty");
            } else if(!bucketPattern.matcher(container).matches()){
                ret = FormValidation.error("The container name does not match with S3 bucket rules");
            }
            return ret;
        }

        public FormValidation doCheckPrefix(@QueryParameter String prefix){
            FormValidation ret = FormValidation.ok();
            if(StringUtils.isBlank(prefix)){
                ret = FormValidation.error("Prefix should have a value");
            } else if(!prefix.endsWith("/")){
                ret = FormValidation.warning("if Prefix point to a folder, it should end with '/' character");
            }
            return ret;
        }

        public FormValidation doCheckRegion(@QueryParameter String region){
            FormValidation ret = FormValidation.ok();
            if(StringUtils.isNotBlank(region) && !Region.DEFAULT_S3.contains(region)){
                ret = FormValidation.error("Region is not valid");
            }
            return ret;
        }

        @Override
        public String getDisplayName() {
            return "Amazon S3";
        }

        public String getPrefix() {
            return PREFIX_PROPERTY;
        }

        public String getContainer() {
            return CONTAINER_PROPERTY;
        }

        public String getRegion() {
            return REGION_PROPERTY;
        }

        public boolean isPropertyConfigured(){
            return StringUtils.isNotBlank(PREFIX_PROPERTY) && StringUtils.isNotBlank(CONTAINER_PROPERTY);
        }

    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("S3BlobStore{");
        sb.append("container='").append(getContainer()).append('\'');
        sb.append(", prefix='").append(getPrefix()).append('\'');
        sb.append(", region='").append(getRegion()).append('\'');
        sb.append('}');
        return sb.toString();
    }
}
