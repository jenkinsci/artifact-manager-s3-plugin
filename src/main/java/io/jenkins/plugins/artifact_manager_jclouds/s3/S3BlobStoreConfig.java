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

import java.util.Collections;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import com.amazonaws.client.builder.AwsClientBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.artifact_manager_jclouds.JCloudsVirtualFile;
import org.apache.commons.lang.StringUtils;
import org.jclouds.aws.AWSResponseException;
import org.jclouds.aws.domain.Region;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Failure;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import com.cloudbees.jenkins.plugins.awscredentials.AmazonWebServicesCredentials;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;

/**
 * Store the S3BlobStore configuration to save it on a separate file. This make that
 * the change of container does not affected to the Artifact functionality, you could change the container
 * and it would still work if both container contains the same data.
 */
@Extension
public class S3BlobStoreConfig extends GlobalConfiguration {

    private static final String BUCKET_REGEXP = "^([a-z]|(\\d(?!\\d{0,2}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})))([a-z\\d]|(\\.(?!(\\.|-)))|(-(?!\\.))){1,61}[a-z\\d\\.]$";
    private static final Pattern bucketPattern = Pattern.compile(BUCKET_REGEXP);

    private static final Logger LOGGER = Logger.getLogger(S3BlobStoreConfig.class.getName());

    @SuppressWarnings("FieldMayBeFinal")
    private static boolean DELETE_ARTIFACTS = Boolean.getBoolean(S3BlobStoreConfig.class.getName() + ".deleteArtifacts");
    @SuppressWarnings("FieldMayBeFinal")
    private static boolean DELETE_STASHES = Boolean.getBoolean(S3BlobStoreConfig.class.getName() + ".deleteStashes");
    /**
     * Session token duration in seconds.
     */
    @SuppressWarnings("FieldMayBeFinal")
    private static int SESSION_DURATION = Integer.getInteger(S3BlobStoreConfig.class.getName() + ".sessionDuration", 3600);

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
    /**
     * AWS credentials to access to the S3 Bucket, if it is empty, it would use the IAM instance profile from the
     * jenkins hosts.
     */
    private String credentialsId;

    /**
     * field to fake S3 endpoint on test.
     */
    static AwsClientBuilder.EndpointConfiguration ENDPOINT;


    /**
     * class to test configuration against Amazon S3 Bucket.
     */
    private static class S3BlobStoreTester extends S3BlobStore {
        private static final long serialVersionUID = -3645770416235883487L;
        private transient S3BlobStoreConfig config;

        public S3BlobStoreTester(String container, String prefix, String region,String credentialsId){
            config = new S3BlobStoreConfig();
            config.setContainer(container);
            config.setPrefix(prefix);
            config.setRegion(region);
            config.setCredentialsId(credentialsId);
        }

        @Override
        public S3BlobStoreConfig getConfiguration() {
            return config;
        }
    }

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

    public String getRegion() {
        return region;
    }

    @DataBoundSetter
    public void setRegion(String region) {
        this.region = region;
        checkValue(doCheckRegion(region));
        save();
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = StringUtils.defaultIfBlank(credentialsId, null);
        save();
    }

    public AmazonWebServicesCredentials getCredentials() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        Optional<AmazonWebServicesCredentials> credential = CredentialsProvider.lookupCredentials(
                AmazonWebServicesCredentials.class, Jenkins.get(),ACL.SYSTEM, Collections.emptyList())
                                                                               .stream()
                                                                               .filter(it -> it.getId().equals(credentialsId))
                                                                               .findFirst();
        if(credential.isPresent()){
            return credential.get();
        } else {
            return null;
        }
    }

    private void checkValue(@NonNull FormValidation formValidation) {
        if (formValidation.kind == FormValidation.Kind.ERROR) {
            throw new Failure(formValidation.getMessage());
        }
    }

    public boolean isDeleteArtifacts() {
        return DELETE_ARTIFACTS;
    }

    public boolean isDeleteStashes() {
        return DELETE_STASHES;
    }

    public int getSessionDuration(){
        return SESSION_DURATION;
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

    public ListBoxModel doFillRegionItems() {
        ListBoxModel regions = new ListBoxModel();
        regions.add("Auto", "");
        for (String s : Region.DEFAULT_REGIONS) {
            regions.add(s);
        }
        return regions;
    }

    @RequirePOST
    public ListBoxModel doFillCredentialsIdItems() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        ListBoxModel credentials = new ListBoxModel();
        credentials.add("IAM instance Profile/user AWS configuration", "");
        credentials.addAll(CredentialsProvider.listCredentials(AmazonWebServicesCredentials.class, Jenkins.get(),
                                                               ACL.SYSTEM, Collections.emptyList(),
                                                               CredentialsMatchers.instanceOf(AmazonWebServicesCredentials.class)));
        return credentials;
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

    public FormValidation doCheckRegion(@QueryParameter String region){
        FormValidation ret = FormValidation.ok();
        if (StringUtils.isNotBlank(region) && !Region.DEFAULT_REGIONS.contains(region)){
            ret = FormValidation.error("Region is not valid");
        }
        return ret;
    }

    @RequirePOST
    public FormValidation doCreateS3Bucket(@QueryParameter String container, @QueryParameter String prefix,
                                                   @QueryParameter String region, @QueryParameter String credentialsId){
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        FormValidation ret = FormValidation.ok("success");
        try {
            S3BlobStore provider = new S3BlobStoreTester(container, prefix, region, credentialsId);
            provider.createS3Bucket(container);
        } catch (Throwable t){
            String msg = processExceptionMessage(t);
            ret = FormValidation.error(StringUtils.abbreviate(msg, 200));
        }
        return ret;
    }

    @RequirePOST
    public FormValidation doValidateS3BucketConfig(@QueryParameter String container, @QueryParameter String prefix,
                                                   @QueryParameter String region, @QueryParameter String credentialsId){
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        FormValidation ret = FormValidation.ok("success");
        try {
            S3BlobStore provider = new S3BlobStoreTester(container, prefix, region, credentialsId);
            JCloudsVirtualFile jc = new JCloudsVirtualFile(provider, container, "");
            jc.list();
        } catch (Throwable t){
            String msg = processExceptionMessage(t);
            ret = FormValidation.error(StringUtils.abbreviate(msg, 200));
        }
        return ret;
    }

    /**
     * it retuns a different cause message based on exception type.
     * @param t Throwable to process.
     * @return the proper cause message.
     */
    private String processExceptionMessage(Throwable t) {
        LOGGER.log(Level.FINEST, t.getMessage(), t);

        String msg = t.getMessage();
        String className = t.getClass().getSimpleName();
        Throwable cause = t.getCause();
        if(cause instanceof AWSResponseException){
            className = cause.getClass().getSimpleName();
            msg = ((AWSResponseException) cause).getError().getMessage();
        }
        return className + ":" + StringUtils.defaultIfBlank(msg, "Unknown error");
    }
}
