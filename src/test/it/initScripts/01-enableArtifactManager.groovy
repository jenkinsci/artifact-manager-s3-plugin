import io.jenkins.plugins.artifact_manager_jclouds.s3.S3BlobStoreConfig
import jenkins.model.ArtifactManagerConfiguration
import io.jenkins.plugins.artifact_manager_jclouds.JCloudsArtifactManagerFactory
import io.jenkins.plugins.artifact_manager_jclouds.s3.S3BlobStore
import io.jenkins.plugins.aws.global_configuration.CredentialsAwsGlobalConfiguration

def factory = new JCloudsArtifactManagerFactory(new S3BlobStore())
println("--- Enabling default artifact storage: ${factory.descriptor.displayName}")

ArtifactManagerConfiguration.get().artifactManagerFactories.add(factory)
S3BlobStoreConfig.get().setContainer(System.getenv("S3_CONTAINER"))
S3BlobStoreConfig.get().setPrefix(System.getenv("S3_PREFIX"))

CredentialsAwsGlobalConfiguration.get().setRegion(System.getenv("S3_REGION"))
