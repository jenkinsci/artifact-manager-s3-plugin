if (infra.isRunningOnJenkinsInfra()) {
    // ci.jenkins.io
    // NOOP on Jenkins infra right now
    //buildPlugin(platforms: ['linux'])
} else if (env.CHANGE_FORK == null) { // TODO pending JENKINS-45970
    // to run tests on S3
    buildArtifactManagerPluginOnAWS()

    // Integration tests with a standard Custom WAR Packager => ATH => PCT flow
    essentialsTest(baseDir: "src/test/it")

    node(){
        stage('Build Docker Image'){
            unarchive: mapping: ["jenkins-war-2.121-artifact-manager-s3-SNAPSHOT.war": "jenkins.war"]
            def dockerFile = """
            FROM jenkins/jenkins:2.121.1
            COPY jenkins.war /usr/share/jenkins/jenkins.war
            """

            writeFile file: "Dockerfile", text: dockerFile
            def customImage = docker.build("artifact-manager-s3:${env.BUILD_ID}")
        }

        stage('Run Docker image'){
            customImage.inside {
                sh 'echo "Hello world"'
            }
        }
    }
} else {
    error 'Run tests manually.'
}
