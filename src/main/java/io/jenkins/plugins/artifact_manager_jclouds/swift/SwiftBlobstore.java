package io.jenkins.plugins.artifact_manager_jclouds.swift;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.lang.StringUtils;
import org.jclouds.ContextBuilder;
import org.jclouds.blobstore.BlobRequestSigner;
import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.http.HttpRequest;
import org.jclouds.openstack.keystone.config.KeystoneProperties;
import org.jclouds.openstack.swift.v1.SwiftApi;
import org.jclouds.openstack.swift.v1.SwiftApiMetadata;
import org.jclouds.openstack.swift.v1.blobstore.RegionScopedBlobStoreContext;
import org.jclouds.openstack.swift.v1.features.AccountApi;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;

import hudson.Extension;
import hudson.Util;
import hudson.util.Secret;
import io.jenkins.plugins.artifact_manager_jclouds.BlobStoreProvider;
import io.jenkins.plugins.artifact_manager_jclouds.BlobStoreProviderDescriptor;

public class SwiftBlobstore extends BlobStoreProvider {

	private static final long serialVersionUID = 5517446936331565739L;

	private static final Logger LOGGER = Logger.getLogger(SwiftBlobstore.class.getName());

	private transient RegionScopedBlobStoreContext blobstoreContext;

	@DataBoundConstructor
	public SwiftBlobstore() {
	}

	@Override
	public String getPrefix() {
		return getConfiguration().getPrefix();
	}

	@Override
	public String getContainer() {
		return getConfiguration().getContainer();
	}

	@Override
	public boolean isDeleteArtifacts() {
		return getConfiguration().isDeleteArtifacts();
	}

	@Override
	public boolean isDeleteStashes() {
		return getConfiguration().isDeleteStashes();
	}

	private SwiftBlobstoreConfig getConfiguration() {
		return SwiftBlobstoreConfig.get();
	}

	private String getRegion() {
		return getConfiguration().getRegion();
	}

	private RegionScopedBlobStoreContext getRegionContext() {
		if (blobstoreContext == null) {
			LOGGER.log(Level.FINER, "Building Swift context");

			SwiftBlobstoreConfig config = getConfiguration();
			final Properties overrides = new Properties();
			UsernamePasswordCredentials credential = getConfiguration().getCredential();

			overrides.put(KeystoneProperties.KEYSTONE_VERSION, "3");
			overrides.put(KeystoneProperties.SCOPE, "project:" + config.getProjectName());
			if (Util.fixEmptyAndTrim(config.getProjectDomain()) != null) {
				overrides.put(KeystoneProperties.PROJECT_DOMAIN_NAME, config.getProjectDomain());
			}


			blobstoreContext = ContextBuilder.newBuilder(new SwiftApiMetadata())
					.credentials(String.format("%s:%s", config.getUserDomain(), credential.getUsername()),
							Secret.toString(credential.getPassword()))
					.overrides(overrides).endpoint(config.getEndpoint()).build(RegionScopedBlobStoreContext.class);

			SwiftApi api = blobstoreContext.unwrapApi(SwiftApi.class);
			api.getContainerApi(getRegion()).create(getContainer());
			AccountApi accountApi = api.getAccountApi(getRegion());
			if (!accountApi.get().getTemporaryUrlKey().isPresent()) {
				accountApi.updateTemporaryUrlKey(UUID.randomUUID().toString());
			}

		}

		return blobstoreContext;
	}

	@Override
	public BlobStoreContext getContext() throws IOException {
		return getRegionContext();
	}

	@Override
	public BlobStore getBlobStore() throws IOException {
		return getRegionContext().getBlobStore(getRegion());
	}

	@Override
	public URI toURI(String container, String key) {
		try {
			Blob blob = getBlobStore().blobBuilder(key).build();
			blob.getMetadata().setContainer(container);
			return toExternalURL(blob, HttpMethod.GET).toURI();
		} catch (IOException | URISyntaxException e) {
			return URI.create(container + "/" + key);
		}
	}

	@Override
	public URL toExternalURL(Blob blob, HttpMethod httpMethod) throws IOException {

		BlobRequestSigner signer = getRegionContext().getSigner(getRegion());

		String container = blob.getMetadata().getContainer();
		String name = blob.getMetadata().getName();

		LOGGER.log(Level.FINER, "Generating presigned URL for {0} / {1} for method {2}",
				new Object[] { container, name, httpMethod });

		HttpRequest request;
		switch (httpMethod) {
		case PUT:
			request = signer.signPutBlob(container, blob, 3600);
			return request.getEndpoint().toURL();
		case GET:
			request = signer.signGetBlob(container, name, 3600);
			return request.getEndpoint().toURL();
		default:
			throw new IOException("HTTP Method " + httpMethod + " not supported for Swift");
		}
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder("SwiftBlobStore{");
		sb.append("container='").append(getContainer()).append('\'');
		sb.append(", prefix='").append(getPrefix()).append('\'');
		sb.append(", region='").append(getRegion()).append('\'');
		sb.append(", deleteArtifacts='").append(isDeleteArtifacts()).append('\'');
		sb.append(", deleteStashes='").append(isDeleteStashes()).append('\'');
		sb.append('}');
		return sb.toString();
	}

	@Symbol("swift")
	@Extension
	public static final class DescriptorImpl extends BlobStoreProviderDescriptor {

		@Override
		public String getDisplayName() {
			return "Openstack Swift";
		}

		/**
		 *
		 * @return true if a container is configured.
		 */
		public boolean isConfigured() {
			return StringUtils.isNotBlank(SwiftBlobstoreConfig.get().getContainer());
		}

	}
}
