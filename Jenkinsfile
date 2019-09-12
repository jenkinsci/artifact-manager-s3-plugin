if (infra.isRunningOnJenkinsInfra()) {
    // ci.jenkins.io
    buildPlugin(configurations: [
        [platform: 'linux', jdk: '8'],
        [platform: 'linux', jdk: '11'],
    ])
} else if (env.CHANGE_FORK == null) { // TODO pending JENKINS-45970
    // to run tests on S3
    buildArtifactManagerPluginOnAWS()
} else {
    error 'Run tests manually.'
}
