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

    def label = "mypod-${UUID.randomUUID().toString()}"
    def name = 'artifact-manager-s3'
    def yaml = """
apiVersion: v1
kind: Pod
metadata:
  generateName: jnlp-
  labels:
    name: jnlp
    label: jnlp
spec:
  containers:
  - name: jnlp
    image: jenkins/jnlp-slave
    tty: true
    securityContext:
      runAsUser: 1000
      allowPrivilegeEscalation: false
  - name: jenkins
    image: artifact-manager-s3:${env.BUILD_ID}
    tty: true
    securityContext:
      runAsUser: 1000
      allowPrivilegeEscalation: false
"""
    timestamps {
      podTemplate(label: label, yaml: yaml){
          node(label) {
            sh 'id'
            stage('Run on k8s'){
              container('jnlp') {
                sh 'id'
              }
              container(name) {
                sh 'id'
              }
            }
          }
        }
    }
} else {
    error 'Run tests manually.'
}
