if (infra.isRunningOnJenkinsInfra()) {
    // ci.jenkins.io
    buildPlugin(configurations: [
    [ platform: "linux", jdk: "8", jenkins: null ],
    [ platform: "linux", jdk: "8",  jenkins: "2.164.1", javaLevel: "8" ],
    ])
} else if (env.CHANGE_FORK == null) { // TODO pending JENKINS-45970
    // to run tests on S3
    buildArtifactManagerPluginOnAWS()
} else {
    error 'Run tests manually.'
}
