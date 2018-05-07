if (infra.isRunningOnJenkinsInfra()) {
    // ci.jenkins.io
    buildPlugin(platforms: ['linux'])

    // Integration tests
    essentialsTest(baseDir: "src/test/it")
} else if (env.CHANGE_FORK == null) { // TODO pending JENKINS-45970
    // to run tests on S3
    buildArtifactManagerPluginOnAWS()
} else {
    error 'Run tests manually.'
}
