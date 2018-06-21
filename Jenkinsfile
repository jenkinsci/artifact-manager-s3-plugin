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
            def customImage
            stage('Build Docker Image'){
                unarchive mapping: ["jenkins-war-2.121-artifact-manager-s3-SNAPSHOT.war": "jenkins.war"]

                sh "pwd && echo '' && ls -la"
                def dockerFile = """
                FROM jenkins/jenkins:2.121.1
                COPY jenkins.war /usr/share/jenkins/jenkins.war
                """

                writeFile file: "Dockerfile", text: dockerFile
                customImage = docker.build("artifact-manager-s3:${env.BUILD_ID}")
            }
    }
    podTemplate(name: 'artifact-manager-s3-k8s', label: 'test-k8s',
          containers: [
            containerTemplate(name: 'artifact-manager-s3-k8s', image: "artifact-manager-s3:${env.BUILD_ID}", ttyEnabled: true),
          ]){
              node('test-k8s') {
                stage('Run Docker image'){
                  container('artifact-manager-s3-k8s') {
                    sh 'echo "Hello world"'
                  }
                }
              }
          }
} else {
    error 'Run tests manually.'
}
