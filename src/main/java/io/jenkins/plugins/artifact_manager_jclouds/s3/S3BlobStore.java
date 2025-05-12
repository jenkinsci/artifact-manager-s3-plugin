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

package io.jenkins.plugins.artifact_manager_jclouds.s3;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Duration;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.common.annotations.VisibleForTesting;
import edu.umd.cs.findbugs.annotations.NonNull;

import jenkins.security.FIPS140;
import org.apache.commons.lang.StringUtils;
import org.jclouds.ContextBuilder;
import org.jclouds.aws.domain.SessionCredentials;
import org.jclouds.aws.s3.AWSS3ProviderMetadata;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.domain.Credentials;
import org.jclouds.location.reference.LocationConstants;
import org.jclouds.osgi.ProviderRegistry;
import org.jclouds.s3.reference.S3Constants;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;

import com.cloudbees.jenkins.plugins.awscredentials.AmazonWebServicesCredentials;
import com.google.common.base.Supplier;

import hudson.Extension;
import io.jenkins.plugins.artifact_manager_jclouds.BlobStoreProvider;
import io.jenkins.plugins.artifact_manager_jclouds.BlobStoreProviderDescriptor;
import io.jenkins.plugins.aws.global_configuration.CredentialsAwsGlobalConfiguration;
import org.jenkinsci.Symbol;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.SystemPropertyCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetUrlRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * Extension that customizes JCloudsBlobStore for AWS S3. Credentials are fetched from the environment, env vars, aws
 * profiles,...
 */
@Restricted(NoExternalUse.class)
public class S3BlobStore extends BlobStoreProvider {

    private static final Logger LOGGER = Logger.getLogger(S3BlobStore.class.getName());

    private static final long serialVersionUID = -8864075675579867370L;

    @DataBoundConstructor
    public S3BlobStore() {
    }

    @Override
    public String getPrefix() {
        return getConfiguration().getPrefix();
    }

    @Override
    public String getContainer() {
        return getConfiguration().getContainer();
    }

    public String getRegion() {
        return CredentialsAwsGlobalConfiguration.get().getRegion();
    }

    public S3BlobStoreConfig getConfiguration(){
        return S3BlobStoreConfig.get();
    }

    @Override
    public boolean isDeleteArtifacts() {
        return getConfiguration().isDeleteArtifacts();
    }

    @Override
    public boolean isDeleteStashes() {
        return getConfiguration().isDeleteStashes();
    }

    @Override
    public BlobStoreContext getContext() throws IOException {
        LOGGER.log(Level.FINEST, "Building context");
        ProviderRegistry.registerProvider(AWSS3ProviderMetadata.builder().build());
        try {
            Properties props = new Properties();
            boolean hasCustomEndpoint = StringUtils.isNotBlank(getConfiguration().getResolvedCustomEndpoint());

            if(StringUtils.isNotBlank(getRegion())) {
                props.setProperty(LocationConstants.PROPERTY_REGIONS, getRegion());
            }
            if (hasCustomEndpoint) {
                // We need to set the endpoint here and in the builder or listing
                // will still use s3.amazonaws.com
                props.setProperty(LocationConstants.ENDPOINT, getConfiguration().getResolvedCustomEndpoint());
            }
            props.setProperty(S3Constants.PROPERTY_S3_VIRTUAL_HOST_BUCKETS, Boolean.toString(!getConfiguration().getUsePathStyleUrl()));

            ContextBuilder builder = ContextBuilder.newBuilder("aws-s3")
                    .credentialsSupplier(getCredentialsSupplier())
                    .overrides(props);

            if (hasCustomEndpoint) {
                builder = builder.endpoint(getConfiguration().getResolvedCustomEndpoint());
            }

            return builder.buildView(BlobStoreContext.class);
        } catch (NoSuchElementException x) {
            throw new IOException(x);
        }
    }

    /**
     * field only for tests.
     */
    static boolean BREAK_CREDS;

    private Supplier<Credentials> credentialsSupplier;

    /**
     * Make tests faster by not using CredentialsAwsGlobalConfiguration.get().sessionCredentials
     * which can very slow especially when using EnvironmentVariableCredentialsProvider or SystemPropertyCredentialsProvider
     */
    @VisibleForTesting
    protected void setCredentialsSupplier(Supplier<Credentials> credentialsSupplier) {
        this.credentialsSupplier = credentialsSupplier;
    }

    /**
     *
     * @return the proper credential supplier using the configuration settings.
     * @throws IOException in case of error.
     */
    private Supplier<Credentials> getCredentialsSupplier() throws IOException {
        if(credentialsSupplier != null) {
            return credentialsSupplier;
        }
        // get user credentials from env vars, profiles,...
        String accessKeyId;
        String secretKey;
        String sessionToken;
        AmazonWebServicesCredentials awsCredentials = CredentialsAwsGlobalConfiguration.get().getCredentials();
        if (getConfiguration().getDisableSessionToken()) {

            if (awsCredentials == null) {
                throw new IOException("No static AWS credentials found");
            }
            accessKeyId = awsCredentials.resolveCredentials().accessKeyId();
            secretKey = awsCredentials.resolveCredentials().secretAccessKey();
            sessionToken = "";
        } else {
            AwsSessionCredentials awsSessionCredentials = CredentialsAwsGlobalConfiguration.get()
                    .sessionCredentials(getRegion(), CredentialsAwsGlobalConfiguration.get().getCredentialsId());
            if(awsSessionCredentials != null ) {
                accessKeyId = awsSessionCredentials.accessKeyId();
                secretKey = awsSessionCredentials.secretAccessKey();
                sessionToken = awsSessionCredentials.sessionToken();
            } else {
                throw new IOException("No session AWS credentials found");
            }
        }

        if (BREAK_CREDS) {
            sessionToken = "<broken>";
        }

        SessionCredentials sessionCredentials = SessionCredentials.builder()
                .accessKeyId(accessKeyId)
                .secretAccessKey(secretKey)
                .sessionToken(sessionToken)
                .build();

        return () -> sessionCredentials;
    }

    @NonNull
    @Override
    public URI toURI(@NonNull String container, @NonNull String key) {
        try (S3Client s3Client = getConfiguration().getAmazonS3ClientBuilder().build()) {
            GetUrlRequest getUrlRequest = GetUrlRequest.builder().key(key).bucket(container).build();
            URI uri = s3Client.utilities().getUrl(getUrlRequest).toURI();
            LOGGER.fine(() -> container + " / " + key + " → " + uri);
            return uri;
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/dev/ShareObjectPreSignedURLJavaSDK.html">Generate a
     *      Pre-signed Object URL using AWS SDK for Java</a>
     */
    @Override
    public URL toExternalURL(@NonNull Blob blob, @NonNull HttpMethod httpMethod) throws IOException {

        String customEndpoint = getConfiguration().getResolvedCustomEndpoint();
        try (S3Client s3Client = getConfiguration().getAmazonS3ClientBuilderWithCredentials().build()) {
            S3Presigner.Builder presignerBuilder = S3Presigner.builder()
                    .fipsEnabled(FIPS140.useCompliantAlgorithms())
                    .credentialsProvider(CredentialsAwsGlobalConfiguration.get().getCredentials())
                    .s3Client(s3Client);
            if (StringUtils.isNotBlank(customEndpoint)) {
                presignerBuilder.endpointOverride(URI.create(customEndpoint));
            }

            String customRegion = getConfiguration().getCustomSigningRegion();
            if(StringUtils.isNotBlank(customRegion)) {
                presignerBuilder.region(Region.of(customRegion));
            }

            S3Configuration s3Configuration = S3Configuration.builder()
                    .pathStyleAccessEnabled(getConfiguration().getUsePathStyleUrl())
                    .accelerateModeEnabled(getConfiguration().getUseTransferAcceleration())
                    .build();
            presignerBuilder.serviceConfiguration(s3Configuration);

            try (S3Presigner presigner = presignerBuilder.build()) {
                Duration expiration = Duration.ofHours(1);
                String container = blob.getMetadata().getContainer();
                String name = blob.getMetadata().getName();
                LOGGER.log(Level.FINE, "Generating presigned URL for {0} / {1} for method {2}",
                        new Object[]{container, name, httpMethod});
                String contentType;
                switch (httpMethod) {
                    case PUT:
                        // Only set content type for upload URLs, so that the right S3 metadata gets set
                        contentType = blob.getMetadata().getContentMetadata().getContentType();
                        PutObjectRequest putObjectRequest = PutObjectRequest.builder().bucket(container)
                                .contentType(contentType)
                                .key(name)
                                .build();
                        PutObjectPresignRequest putObjectPresignRequest = PutObjectPresignRequest.builder()
                                .signatureDuration(expiration)
                                .putObjectRequest(putObjectRequest).build();
                        return presigner.presignPutObject(putObjectPresignRequest).url();
                    case GET:
                        GetObjectRequest getObjectRequest = GetObjectRequest.builder().bucket(container).key(name).build();
                        GetObjectPresignRequest getObjectPresignRequest = GetObjectPresignRequest.builder()
                                .signatureDuration(expiration)
                                .getObjectRequest(getObjectRequest).build();
                        return presigner.presignGetObject(getObjectPresignRequest).url();
                    default:
                        throw new IOException("HTTP Method " + httpMethod + " not supported for S3");
                }

            }
        }
    }

    @Symbol("s3")
    @Extension
    public static final class DescriptorImpl extends BlobStoreProviderDescriptor {

        @Override
        public String getDisplayName() {
            return "Amazon S3";
        }

        /**
         *
         * @return true if a container is configured.
         */
        public boolean isConfigured(){
            return StringUtils.isNotBlank(S3BlobStoreConfig.get().getContainer());
        }

    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("S3BlobStore{");
        sb.append("container='").append(getContainer()).append('\'');
        sb.append(", prefix='").append(getPrefix()).append('\'');
        sb.append(", region='").append(getRegion()).append('\'');
        sb.append(", deleteArtifacts='").append(isDeleteArtifacts()).append('\'');
        sb.append(", deleteStashes='").append(isDeleteStashes()).append('\'');
        sb.append('}');
        return sb.toString();
    }

}
