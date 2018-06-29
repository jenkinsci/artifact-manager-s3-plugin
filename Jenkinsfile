if (infra.isRunningOnJenkinsInfra()) {
    // ci.jenkins.io
    // NOOP on Jenkins infra right now
    //buildPlugin(platforms: ['linux'])
} else if (env.CHANGE_FORK == null) { // TODO pending JENKINS-45970
    // to run tests on S3
    buildArtifactManagerPluginOnAWS()

    // Integration tests with a standard Custom WAR Packager => ATH => PCT flow
    cdkata("cdkata.yml", "docker && highmem", "src/test/it")
    
    //build a custom docker image, finally deploy it in k8s and run some jobs.
    buildAMOnS3RunK8s()
} else {
    error 'Run tests manually.'
}
