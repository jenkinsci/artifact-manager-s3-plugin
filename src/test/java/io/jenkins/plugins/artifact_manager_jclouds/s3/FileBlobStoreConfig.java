package io.jenkins.plugins.artifact_manager_jclouds.s3;

import java.util.logging.Logger;
import javax.annotation.Nonnull;
import org.kohsuke.stapler.StaplerRequest;
import hudson.Extension;
import hudson.util.FormValidation;
import jenkins.model.GlobalConfiguration;

/**
 * Fake configuration to expose a REST API to attend http request.
 * It does not store anything, and always return "success"
 */
@Extension
public class FileBlobStoreConfig extends GlobalConfiguration {
    private static final Logger LOGGER = Logger.getLogger(FileBlobStoreConfig.class.getName());


    public FormValidation doExternalURL(StaplerRequest request){
        String name = request.getParameter("fileName");
        String container = request.getParameter("container");
        String method = request.getMethod();
        LOGGER.info("Method = " + method + "-FileName = " + name + "-Container = " + container);
        return FormValidation.ok("success");
    }

    @Nonnull
    @Override
    public String getDisplayName() {
        return "Fake settings";
    }
}
