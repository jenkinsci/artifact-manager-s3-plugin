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
import java.util.regex.Pattern;

import javax.annotation.Nonnull;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.Bucket;
import com.amazonaws.services.s3.model.BucketAccelerateConfiguration;
import com.amazonaws.services.s3.model.BucketAccelerateStatus;
import com.amazonaws.services.s3.model.SetBucketAccelerateConfigurationRequest;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.Util;
import hudson.model.Failure;
import hudson.util.FormValidation;
import io.jenkins.plugins.artifact_manager_jclouds.JCloudsVirtualFile;
import io.jenkins.plugins.aws.global_configuration.AbstractAwsGlobalConfiguration;
import io.jenkins.plugins.aws.global_configuration.CredentialsAwsGlobalConfiguration;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;

/**
 * Store the S3BlobStore configuration to save it on a separate file. This make that
 * the change of container does not affected to the Artifact functionality, you could change the container
 * and it would still work if both container contains the same data.
 */
@Symbol("s3")
@Extension
public class S3BlobStoreConfig extends AbstractAwsGlobalConfiguration {

    private static final String BUCKET_REGEXP = "^([a-z]|(\\d(?!\\d{0,2}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})))([a-z\\d]|(\\.(?!(\\.|-)))|(-(?!\\.))){1,61}[a-z\\d\\.]$";
    private static final Pattern bucketPattern = Pattern.compile(BUCKET_REGEXP);

    private static final String ENDPOINT_REGEXP = "^[a-z0-9][a-z0-9-.]{0,}(?::[0-9]{1,5})?$";
    private static final Pattern endPointPattern = Pattern.compile(ENDPOINT_REGEXP, Pattern.CASE_INSENSITIVE);
    
    @SuppressWarnings("FieldMayBeFinal")
    private static boolean DELETE_ARTIFACTS = Boolean.getBoolean(S3BlobStoreConfig.class.getName() + ".deleteArtifacts");
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
    @Deprecated private transient String region, credentialsId;

    private boolean usePathStyleUrl;
    
    private boolean useHttp;
    
    private boolean useTransferAcceleration;

    private boolean disableSessionToken;
    
    private String customEndpoint;
    
    private String customSigningRegion;
    
    private final boolean deleteArtifacts;
    
    private final boolean deleteStashes;
    
    /**
     * class to test configuration against Amazon S3 Bucket.
     */
    private static class S3BlobStoreTester extends S3BlobStore {
        private static final long serialVersionUID = -3645770416235883487L;
        private transient S3BlobStoreConfig config;

        S3BlobStoreTester(String container, String prefix, boolean useHttp,
            boolean useTransferAcceleration, boolean usePathStyleUrl,
            boolean disableSessionToken, String customEndpoint,
            String customSigningRegion) {
            config = new S3BlobStoreConfig();
            config.setContainer(container);
            config.setPrefix(prefix);
            config.setCustomEndpoint(customEndpoint);
            config.setCustomSigningRegion(customSigningRegion);
            config.setUseHttp(useHttp);
            config.setUseTransferAcceleration(useTransferAcceleration);
            config.setUsePathStyleUrl(usePathStyleUrl);
            config.setDisableSessionToken(disableSessionToken);
        }

        @Override
        public S3BlobStoreConfig getConfiguration() {
            return config;
        }
    }

    public S3BlobStoreConfig() {
        load();
        if (Util.fixEmpty(region) != null || Util.fixEmpty(credentialsId) != null) {
            CredentialsAwsGlobalConfiguration.get().setRegion(region);
            CredentialsAwsGlobalConfiguration.get().setCredentialsId(credentialsId);
            region = null;
            credentialsId = null;
            save();
        }
        deleteArtifacts = DELETE_ARTIFACTS;
        deleteStashes = DELETE_STASHES;
    }

    public String getContainer() {
        return container;
    }

    @DataBoundSetter
    public void setContainer(String container) {
        this.container = container;
        checkValue(doCheckContainer(container));
        save();
    }

    public String getPrefix() {
        return prefix;
    }

    @DataBoundSetter
    public void setPrefix(String prefix){
        this.prefix = prefix;
        checkValue(doCheckPrefix(prefix));
        save();
    }

    private void checkValue(@NonNull FormValidation formValidation) {
        if (formValidation.kind == FormValidation.Kind.ERROR) {
            throw new Failure(formValidation.getMessage());
        }
    }

    public boolean isDeleteArtifacts() {
        return deleteArtifacts;
    }

    public boolean isDeleteStashes() {
        return deleteStashes;
    }
    
    public boolean getUsePathStyleUrl() {
        return usePathStyleUrl;
    }
    
    @DataBoundSetter
    public void setUsePathStyleUrl(boolean usePathStyleUrl){
        this.usePathStyleUrl = usePathStyleUrl;
        save();
    }
    
    public boolean getUseHttp() {
        return useHttp;
    }

    @DataBoundSetter
    public void setUseHttp(boolean useHttp){
        this.useHttp = useHttp;
        save();
    }
    
    public boolean getUseTransferAcceleration() {
        return useTransferAcceleration;
    }

    @DataBoundSetter
    public void setUseTransferAcceleration(boolean useTransferAcceleration){
        this.useTransferAcceleration = useTransferAcceleration;
        save();
    }

    public boolean getDisableSessionToken() {
        return disableSessionToken;
    }

    @DataBoundSetter
    public void setDisableSessionToken(boolean disableSessionToken){
        this.disableSessionToken = disableSessionToken;
        save();
    }
    
    public String getCustomEndpoint() {
        return customEndpoint;
    }
    
    @DataBoundSetter
    public void setCustomEndpoint(String customEndpoint){
        checkValue(doCheckCustomEndpoint(customEndpoint));
        this.customEndpoint = customEndpoint;
        save();
    }
    
    public String getResolvedCustomEndpoint() {
        if(StringUtils.isNotBlank(customEndpoint)) {
            String protocol;
            if(getUseHttp()) {
                protocol = "http";
            } else {
                protocol = "https";
            }
            return protocol + "://" + customEndpoint;
        }
        return null;
    }
    
    public String getCustomSigningRegion() {
        return customSigningRegion;
    }
    
    @DataBoundSetter
    public void setCustomSigningRegion(String customSigningRegion){
        this.customSigningRegion = customSigningRegion;
        checkValue(doCheckCustomSigningRegion(this.customSigningRegion));
        save();
    }
    
    @Nonnull
    @Override
    public String getDisplayName() {
        return "Amazon S3 Bucket Access settings";
    }

    @Nonnull
    public static S3BlobStoreConfig get() {
        return ExtensionList.lookupSingleton(S3BlobStoreConfig.class);
    }

    /**
    *
    * @return an AmazonS3ClientBuilder using the region or not, it depends if a region is configured or not.
    */
    AmazonS3ClientBuilder getAmazonS3ClientBuilder() {
        AmazonS3ClientBuilder ret = AmazonS3ClientBuilder.standard();

        if (StringUtils.isNotBlank(customEndpoint)) {
            String resolvedCustomSigningRegion = customSigningRegion;
            if (StringUtils.isBlank(resolvedCustomSigningRegion)) {
                resolvedCustomSigningRegion = "us-east-1";
            }
            ret = ret.withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(getResolvedCustomEndpoint(), resolvedCustomSigningRegion));
        } else if (StringUtils.isNotBlank(CredentialsAwsGlobalConfiguration.get().getRegion())) {
            ret = ret.withRegion(CredentialsAwsGlobalConfiguration.get().getRegion());
        } else {
            ret = ret.withForceGlobalBucketAccessEnabled(true);
        }
        ret = ret.withAccelerateModeEnabled(useTransferAcceleration);

        // TODO the client would automatically use path-style URLs under certain conditions; is it really necessary to override?
        ret = ret.withPathStyleAccessEnabled(getUsePathStyleUrl());

        return ret;
    }

    AmazonS3ClientBuilder getAmazonS3ClientBuilderWithCredentials() throws IOException {
        return getAmazonS3ClientBuilderWithCredentials(getDisableSessionToken());
    }

    private AmazonS3ClientBuilder getAmazonS3ClientBuilderWithCredentials(boolean disableSessionToken) throws IOException {
        AmazonS3ClientBuilder builder = getAmazonS3ClientBuilder();
        if (disableSessionToken) {
            builder = builder.withCredentials(CredentialsAwsGlobalConfiguration.get().getCredentials());
        } else {
            AWSStaticCredentialsProvider credentialsProvider = new AWSStaticCredentialsProvider(
            CredentialsAwsGlobalConfiguration.get().sessionCredentials(builder));
            builder = builder.withCredentials(credentialsProvider);
        }
        return builder;
    }
    
    public FormValidation doCheckContainer(@QueryParameter String container){
        FormValidation ret = FormValidation.ok();
        if (StringUtils.isBlank(container)){
            ret = FormValidation.warning("The container name cannot be empty");
        } else if (!bucketPattern.matcher(container).matches()){
            ret = FormValidation.error("The S3 Bucket name does not match S3 bucket rules");
        }
        return ret;
    }

    public FormValidation doCheckPrefix(@QueryParameter String prefix){
        FormValidation ret;
        if (StringUtils.isBlank(prefix)) {
            ret = FormValidation.ok("Artifacts will be stored in the root folder of the S3 Bucket.");
        } else if (prefix.endsWith("/")) {
            ret = FormValidation.ok();
        } else {
            ret = FormValidation.error("A prefix must end with a slash.");
        }
        return ret;
    }

    public FormValidation doCheckCustomSigningRegion(@QueryParameter String customSigningRegion) {
        FormValidation ret;
        if (StringUtils.isBlank(customSigningRegion) && StringUtils.isNotBlank(customEndpoint)) {
            ret = FormValidation.ok("'us-east-1' will be used when a custom endpoint is configured and custom signing region is blank.");
        } else {
            ret = FormValidation.ok();
        }
        return ret;
    }

    public FormValidation doCheckCustomEndpoint(@QueryParameter String customEndpoint) {
        FormValidation ret = FormValidation.ok();
        if (!StringUtils.isBlank(customEndpoint) && !endPointPattern.matcher(customEndpoint).matches()) {
            ret = FormValidation.error("Custom Endpoint may not be valid.");
        }
        return ret;
    }

    /**
     * create an S3 Bucket.
     * @param name name of the S3 Bucket.
     * @return return the Bucket created.
     * @throws IOException in case of error obtaining the credentials, in other kind of errors it will throw the
     * runtime exceptions are thrown by createBucket method.
     */
    public Bucket createS3Bucket(String name) throws IOException {
        return createS3Bucket(name, getDisableSessionToken());
    }

    private Bucket createS3Bucket(String name, boolean disableSessionToken) throws IOException {
        AmazonS3ClientBuilder builder = getAmazonS3ClientBuilderWithCredentials(disableSessionToken);
        //Accelerated mode must be off in order to apply it to a bucket
        AmazonS3 client = builder.withAccelerateModeEnabled(false).build();
        Bucket bucket = client.createBucket(name);
        if(useTransferAcceleration) {
            client.setBucketAccelerateConfiguration(new SetBucketAccelerateConfigurationRequest(name,
                new BucketAccelerateConfiguration(BucketAccelerateStatus.Enabled)));
        }
        return bucket;
    }

    @RequirePOST
    public FormValidation doCreateS3Bucket(@QueryParameter String container, @QueryParameter boolean disableSessionToken) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        FormValidation ret = FormValidation.ok("success");
        try {
            createS3Bucket(container, disableSessionToken);
        } catch (Throwable t){
            String msg = processExceptionMessage(t);
            ret = FormValidation.error(StringUtils.abbreviate(msg, 200));
        }
        return ret;
    }

    void checkGetBucketLocation(String container, boolean disableSessionToken) throws IOException {
        AmazonS3ClientBuilder builder = getAmazonS3ClientBuilderWithCredentials(disableSessionToken);
        AmazonS3 client = builder.build();
        client.getBucketLocation(container);
    }

    @RequirePOST
    public FormValidation doValidateS3BucketConfig(
            @QueryParameter String container, 
            @QueryParameter String prefix,
            @QueryParameter boolean useHttp,
            @QueryParameter boolean useTransferAcceleration,
            @QueryParameter boolean usePathStyleUrl,
            @QueryParameter boolean disableSessionToken, 
            @QueryParameter String customEndpoint,
            @QueryParameter String customSigningRegion) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        FormValidation ret = FormValidation.ok("success");
        
        S3BlobStore provider = new S3BlobStoreTester(container, prefix, 
                useHttp, useTransferAcceleration,usePathStyleUrl,
                disableSessionToken, customEndpoint, customSigningRegion);
        
        try {
            JCloudsVirtualFile jc = new JCloudsVirtualFile(provider, container, prefix);
            jc.list();
        } catch (Throwable t){
            String msg = processExceptionMessage(t);
            ret = FormValidation.error(StringUtils.abbreviate(msg, 200));
        }
        try {
            provider.getConfiguration().checkGetBucketLocation(container, disableSessionToken);
        } catch (Throwable t){
            ret = FormValidation.warning(t, "GetBucketLocation failed");
        }
        return ret;
    }

}
