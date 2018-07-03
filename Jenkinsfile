if (infra.isRunningOnJenkinsInfra()) {
    // ci.jenkins.io
    // NOOP on Jenkins infra right now
    //buildPlugin(platforms: ['linux'])
} else if (env.CHANGE_FORK == null) { // TODO pending JENKINS-45970
    // to run tests on S3
    buildArtifactManagerPluginOnAWS()

    // Integration tests with a standard Custom WAR Packager => ATH => PCT flow
    cdkata("cdkata.yml", "docker && highmem", "src/test/it", true)
    
    //build a custom docker image, finally deploy it in k8s and run some jobs.
    runAMOnS3K8s("src/test/it")
} else {
    error 'Run tests manually.'
}
