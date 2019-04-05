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
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nonnull;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.AbortMultipartUploadRequest;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadResult;
import com.amazonaws.services.s3.model.PartETag;
import org.apache.commons.lang.StringUtils;
import org.jclouds.ContextBuilder;
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

import com.amazonaws.auth.AWSSessionCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.google.common.base.Supplier;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import io.jenkins.plugins.artifact_manager_jclouds.BlobStoreProvider;
import io.jenkins.plugins.artifact_manager_jclouds.BlobStoreProviderDescriptor;
import io.jenkins.plugins.aws.global_configuration.CredentialsAwsGlobalConfiguration;
import org.jenkinsci.Symbol;

/**
 * Extension that customizes JCloudsBlobStore for AWS S3. Credentials are fetched from the environment, env vars, aws
 * profiles,...
 */
@Restricted(NoExternalUse.class)
public class S3BlobStore extends BlobStoreProvider {

    private static final Logger LOGGER = Logger.getLogger(S3BlobStore.class.getName());

    private static final long serialVersionUID = -8864075675579867370L;

    private class S3MultipartUploader implements MultipartUploader, AutoCloseable {
        private final String uploadID;
        private Blob blob;

        private S3MultipartUploader(Blob blob, String uploadID) {
            this.blob = blob;
            this.uploadID = uploadID;
        }

        @NonNull
        @Override
        public URL toExternalURL(int partNumber) throws IOException {
            return buildExternalURL(blob, HttpMethod.PUT, partNumber, uploadID);
        }

        @Override
        public void complete(List<Part> etags) throws IOException {
            if (blob == null) {
                return;
            }
            String container = blob.getMetadata().getContainer();
            String name = blob.getMetadata().getName();

            List<PartETag> partETags = new ArrayList<>();
            for (Part part : etags) {
                partETags.add(new PartETag(part.getPartNumber(), part.getETag()));
            }

            CompleteMultipartUploadRequest request = new CompleteMultipartUploadRequest();
            request.setBucketName(container);
            request.setKey(name);
            request.setUploadId(uploadID);
            request.setPartETags(partETags);
            client().completeMultipartUpload(request);
            blob = null;
        }

        @Override
        public void close() throws IOException {
            if (blob == null) {
                return;
            }
            String container = blob.getMetadata().getContainer();
            String name = blob.getMetadata().getName();
            client().abortMultipartUpload(new AbortMultipartUploadRequest(container, name, uploadID));
            blob = null;
        }
    }

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

    /**
     * field only for tests.
     */
    static boolean BREAK_CREDS;

    /**
     *
     * @return the proper credential supplier using the configuration settings.
     * @throws IOException in case of error.
     */
    private Supplier<Credentials> getCredentialsSupplier() throws IOException {
        // get user credentials from env vars, profiles,...
        AmazonS3ClientBuilder builder = getConfiguration().getAmazonS3ClientBuilder();
        AWSSessionCredentials awsCredentials = CredentialsAwsGlobalConfiguration.get().sessionCredentials(builder);
        String sessionToken = awsCredentials.getSessionToken();
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

    @NonNull
    private AmazonS3 client() throws IOException {
        AmazonS3ClientBuilder builder = getConfiguration().getAmazonS3ClientBuilder();
        AWSSessionCredentials sessionCredentials = CredentialsAwsGlobalConfiguration.get().sessionCredentials(builder);
        AWSStaticCredentialsProvider credentialsProvider = new AWSStaticCredentialsProvider(sessionCredentials);
        return builder.withCredentials(credentialsProvider).build();
    }

    /**
     * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/dev/ShareObjectPreSignedURLJavaSDK.html">Generate a
     *      Pre-signed Object URL using AWS SDK for Java</a>
     */
    @Override
    @NonNull
    public URL toExternalURL(@NonNull Blob blob, @NonNull HttpMethod httpMethod) throws IOException {
        return buildExternalURL(blob, httpMethod, 0, null);
    }

    @NonNull
    private URL buildExternalURL(@NonNull Blob blob, @NonNull HttpMethod httpMethod, int partNumber, String uploadID) throws IOException {
        assert blob != null;
        assert httpMethod != null;

        Date expiration = new Date(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1));
        String container = blob.getMetadata().getContainer();
        String name = blob.getMetadata().getName();
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
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(container, name, awsMethod);
        request.setExpiration(expiration);
        if (StringUtils.isNotEmpty(uploadID)) {
            request.addRequestParameter("partNumber", Integer.toString(partNumber));
            request.addRequestParameter("uploadId", uploadID);
            LOGGER.log(Level.FINE, "Generating presigned URL for {0} / {1} for method {2} (part {3})",
                    new Object[]{container, name, httpMethod, partNumber});
        } else {
            LOGGER.log(Level.FINE, "Generating presigned URL for {0} / {1} for method {2}",
                    new Object[]{container, name, httpMethod});
        }
        return client().generatePresignedUrl(request);
    }

    @NonNull
    public MultipartUploader initiateMultipartUpload(@NonNull Blob blob) throws IOException {
        assert blob != null;

        String container = blob.getMetadata().getContainer();
        String name = blob.getMetadata().getName();
        LOGGER.log(Level.FINE, "Initiate multipart upload for {0} / {1}",
                new Object[]{container, name});

        InitiateMultipartUploadRequest request = new InitiateMultipartUploadRequest(container, name);
        InitiateMultipartUploadResult result = client().initiateMultipartUpload(request);
        return new S3MultipartUploader(blob, result.getUploadId());
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
