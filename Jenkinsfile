if (infra.isRunningOnJenkinsInfra()) {
    // ci.jenkins.io
    buildPlugin(platforms: ['linux'])

    // Integration tests with a standard CUstom WAR Packager => ATH => PCT flow
    essentialsTest(baseDir: "src/test/it")
    sh "ls -lah tmp/output/target/${ARTIFACT_ID}-${VERSION}.war"
} else if (env.CHANGE_FORK == null) { // TODO pending JENKINS-45970
    // to run tests on S3
    buildArtifactManagerPluginOnAWS()
} else {
    error 'Run tests manually.'
}
