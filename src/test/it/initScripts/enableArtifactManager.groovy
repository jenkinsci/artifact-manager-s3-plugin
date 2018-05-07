import jenkins.model.ArtifactManagerConfiguration
import io.jenkins.plugins.artifact_manager_s3.JCloudsArtifactManager
import io.jenkins.plugins.artifact_manager_s3.JCloudsArtifactManagerFactory

// Predefine storage and prefix when run in test on AWS
if (Boolean.getBoolean("artifact-manager-s3.enabled")) {
    println("--- Enabling default artifact storage: ${factory.descriptor.displayName}")

    def factory = new JCloudsArtifactManagerFactory();
    ArtifactManagerConfiguration.get().artifactManagerFactories.add(factory)
    JCloudsArtifactManager.@BLOB_CONTAINER = "artifact-manager-s3"
    JCloudsArtifactManager.@PREFIX = "integration-tests/"
}
