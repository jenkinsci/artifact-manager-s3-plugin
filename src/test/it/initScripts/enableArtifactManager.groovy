import jenkins.model.ArtifactManagerConfiguration
import io.jenkins.plugins.artifact_manager_jclouds.JCloudsArtifactManagerFactory
import io.jenkins.plugins.artifact_manager_s3.S3BlobStore

// Predefine storage and prefix when run in test on AWS
if (Boolean.getBoolean("artifact-manager-s3.enabled")) {
    def factory = new JCloudsArtifactManagerFactory(new S3BlobStore());
    println("--- Enabling default artifact storage: ${factory.descriptor.displayName}")

    ArtifactManagerConfiguration.get().artifactManagerFactories.add(factory)
    S3BlobStore.@BLOB_CONTAINER = "artifact-manager-s3"
    S3BlobStore.@PREFIX = "integration-tests/"
}
