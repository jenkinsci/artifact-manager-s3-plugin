if (infra.isRunningOnJenkinsInfra()) {
    // ci.jenkins.io
    buildPlugin(platforms: ['linux'])

    // Integration tests with a standard CUstom WAR Packager => ATH => PCT flow
    essentialsTest(baseDir: "src/test/it")
} else if (env.CHANGE_FORK == null) { // TODO pending JENKINS-45970
    // to run tests on S3
    buildArtifactManagerPluginOnAWS()
} else {
    error 'Run tests manually.'
}
