import io.jenkins.plugins.artifact_manager_jclouds.s3.S3BlobStoreConfig
import jenkins.model.ArtifactManagerConfiguration
import io.jenkins.plugins.artifact_manager_jclouds.JCloudsArtifactManagerFactory
import io.jenkins.plugins.artifact_manager_jclouds.s3.S3BlobStore


def factory = new JCloudsArtifactManagerFactory(new S3BlobStore());
println("--- Enabling default artifact storage: ${factory.descriptor.displayName}")

ArtifactManagerConfiguration.get().artifactManagerFactories.add(factory)
S3BlobStoreConfig.get().setContainer("cloudbees-arch-us-west-2")
S3BlobStoreConfig.get().setPrefix("integration-tests/")
S3BlobStoreConfig.get().setRegion('us-west-2')
