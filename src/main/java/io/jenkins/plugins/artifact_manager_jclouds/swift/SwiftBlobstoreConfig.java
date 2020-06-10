package io.jenkins.plugins.artifact_manager_jclouds.swift;

import java.util.Collections;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nonnull;

import org.apache.commons.lang.StringUtils;
import org.jclouds.Constants;
import org.jclouds.ContextBuilder;
import org.jclouds.openstack.keystone.config.KeystoneProperties;
import org.jclouds.openstack.swift.v1.SwiftApi;
import org.jclouds.openstack.swift.v1.SwiftApiMetadata;
import org.jenkinsci.Symbol;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.QueryParameter;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;

import hudson.Extension;
import hudson.ExtensionList;
import hudson.Util;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;

@Symbol("swift")
@Extension
public class SwiftBlobstoreConfig extends GlobalConfiguration {

	private static final Logger LOGGER = Logger.getLogger(SwiftBlobstoreConfig.class.getName());

	private String endpoint;
	private String region;
	private String credentialsId;
	private String userDomain;
	private String projectName;
	private String projectDomain;

	private String container;
	private String prefix;

	private boolean deleteArtifacts;
	private boolean deleteStashes;

	public SwiftBlobstoreConfig() {
		load();
	}

	public boolean isDeleteArtifacts() {
		return deleteArtifacts;
	}

	public void setDeleteArtifacts(boolean deleteArtifacts) {
		this.deleteArtifacts = deleteArtifacts;
		save();
	}

	public boolean isDeleteStashes() {
		return deleteStashes;
	}

	public void setDeleteStashes(boolean deleteStashes) {
		this.deleteStashes = deleteStashes;
		save();
	}

	public String getEndpoint() {
		return endpoint;
	}

	public void setEndpoint(String endpoint) {
		this.endpoint = endpoint;
		save();
	}

	public String getRegion() {
		return region;
	}

	public void setRegion(String region) {
		this.region = region;
		save();
	}

	public String getCredentialsId() {
		return credentialsId;
	}

	public void setCredentialsId(String credentialId) {
		this.credentialsId = credentialId;
		save();
	}

	public String getUserDomain() {
		return userDomain;
	}

	public void setUserDomain(String userDomain) {
		this.userDomain = userDomain;
		save();
	}

	public String getProjectName() {
		return projectName;
	}

	public void setProjectName(String projectName) {
		this.projectName = projectName;
		save();
	}

	public String getProjectDomain() {
		return projectDomain;
	}

	public void setProjectDomain(String projectDomain) {
		this.projectDomain = projectDomain;
		save();
	}

	public String getContainer() {
		return container;
	}

	public void setContainer(String container) {
		this.container = container;
		save();
	}

	public String getPrefix() {
		return prefix;
	}

	public void setPrefix(String prefix) {
		this.prefix = prefix;
		save();
	}

	public ListBoxModel doFillCredentialsIdItems(@QueryParameter String credentialsId) {
		StandardListBoxModel result = new StandardListBoxModel();
		return result.includeEmptyValue().includeAs(ACL.SYSTEM, Jenkins.get(), UsernamePasswordCredentialsImpl.class)
				.includeCurrentValue(credentialsId);
	}

	private UsernamePasswordCredentials getCredential(String credentialsId) {
		UsernamePasswordCredentialsImpl c = CredentialsMatchers
				.firstOrNull(
						CredentialsProvider.lookupCredentials(UsernamePasswordCredentialsImpl.class, Jenkins.get(),
								ACL.SYSTEM, Collections.<DomainRequirement>emptyList()),
						CredentialsMatchers.withId(credentialsId));

		return c;
	}

	@Restricted(NoExternalUse.class)
	UsernamePasswordCredentials getCredential() {
		return getCredential(credentialsId);
	}

	public FormValidation doCheckContainer(@QueryParameter String container) {
		FormValidation ret = FormValidation.ok();
		if (StringUtils.isBlank(container)) {
			ret = FormValidation.error("The container name cannot be empty");
		} else if (container.contains("/")) {
			ret = FormValidation.error("The container name cannot include the character '/'");
		} else if (container.length() > 256) {
			ret = FormValidation.error("The container name cannot exceed 256 characters");
		}
		return ret;
	}

	public FormValidation doCheckPrefix(@QueryParameter String prefix) {
		FormValidation ret;
		if (StringUtils.isBlank(prefix)) {
			ret = FormValidation.ok("Artifacts/Stashes will be stored in the root folder of the Swift container.");
		} else if (prefix.endsWith("/")) {
			ret = FormValidation.ok();
		} else {
			ret = FormValidation.error("A prefix must end with a slash.");
		}
		return ret;
	}

	public ListBoxModel doFillRegionItems(@QueryParameter String region, @QueryParameter String endpoint,
			@QueryParameter String credentialsId, @QueryParameter String userDomain, @QueryParameter String projectName,
			@QueryParameter String projectDomain) {
		StandardListBoxModel result = new StandardListBoxModel();

		if (StringUtils.isBlank(endpoint) || StringUtils.isBlank(credentialsId) || StringUtils.isBlank(userDomain)
				|| StringUtils.isBlank(projectName)) {
			if (!StringUtils.isBlank(region)) {
				result.add(region);
			}
			return result;
		}

		final Properties overrides = new Properties();

		overrides.put(KeystoneProperties.KEYSTONE_VERSION, "3");
		overrides.put(KeystoneProperties.SCOPE, "project:" + projectName);
		overrides.put(Constants.PROPERTY_MAX_RETRIES, "0");
		if (Util.fixEmptyAndTrim(projectDomain) != null) {
			overrides.put(KeystoneProperties.PROJECT_DOMAIN_NAME, projectDomain);
		}

		UsernamePasswordCredentials credential = getCredential(credentialsId);

		try {
			SwiftApi api = ContextBuilder.newBuilder(new SwiftApiMetadata())
					.credentials(String.format("%s:%s", userDomain, credential.getUsername()),
							Secret.toString(credential.getPassword()))
					.overrides(overrides).endpoint(endpoint).buildApi(SwiftApi.class);

			for (String r : api.getConfiguredRegions())
				result.add(r);
		} catch (Exception e) {
			LOGGER.log(Level.WARNING, "Failed to log in to Swift", e);
			if (!StringUtils.isBlank(region)) {
				result.add(region);
			}
			return result;
		}

		return result;
	}

	@Nonnull
	public static SwiftBlobstoreConfig get() {
		return ExtensionList.lookupSingleton(SwiftBlobstoreConfig.class);
	}
}
