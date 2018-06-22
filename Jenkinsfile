if (infra.isRunningOnJenkinsInfra()) {
    // ci.jenkins.io
    // NOOP on Jenkins infra right now
    //buildPlugin(platforms: ['linux'])
} else if (env.CHANGE_FORK == null) { // TODO pending JENKINS-45970
    // to run tests on S3
    buildArtifactManagerPluginOnAWS()

    // Integration tests with a standard Custom WAR Packager => ATH => PCT flow
    essentialsTest(baseDir: "src/test/it")

def name = 'artifact-manager-s3'
def label = "${name}-${UUID.randomUUID().toString()}"
def baseDir = "src/test/it"
def yamlDinD = """
apiVersion: v1
kind: Pod
metadata:
  generateName: ${name}-
  labels:
    name: jnlp
    label: jnlp
spec:
  securityContext:
    runAsUser: 1000
  containers:
  - name: jnlp
    image: jenkins/jnlp-slave
    tty: true
    securityContext:
      runAsUser: 1000
      allowPrivilegeEscalation: false
  - name: docker
    image: docker:dind
    tty: true
    securityContext:
      runAsUser: 0
      privileged: true
"""
    timestamps {
      podTemplate(label: label, yaml: yamlDinD) {
          node(label){
            stage('Build Docker Image'){
                infra.checkout()
                dir(baseDir) {
                  unarchive mapping: ["jenkins-war-2.121-artifact-manager-s3-SNAPSHOT.war": "jenkins.war"]
                  def dockerFile = """
                  FROM jenkins/jenkins:2.121.1
                  COPY jenkins.war /usr/share/jenkins/jenkins.war
                  COPY jenkins_home /var/jenkins_home
                  COPY initScripts/enableArtifactManager.groovy /var/jenkins_home/init.groovy.d/enableArtifactManager.groovy
                  RUN chown -R jenkins:jenkins /var/jenkins_home
                  """
                  container('docker'){
                      docker.withRegistry('https://docker.cloudbees.com', '80ca7cb9-b576-43df-9f54-ac49882dd7a9') {
                          writeFile file: "Dockerfile", text: dockerFile
                          def customImage = docker.build("${name}:${env.BUILD_ID}")
                          customImage.push()
                      }
                  }
                }
              }
            }
    }

label = "${name}-${UUID.randomUUID().toString()}"
def yaml = """
apiVersion: v1
kind: Pod
metadata:
  generateName: ${name}-
  labels:
    name: jnlp
    label: jnlp
spec:
  securityContext:
    runAsUser: 1000
  containers:
  - name: jnlp
    image: jenkins/jnlp-slave
    tty: true
    securityContext:
      runAsUser: 1000
      allowPrivilegeEscalation: false
  - name: ${name}
    image: ${name}:${env.BUILD_ID}
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
                sh 'ls -la /var/jenkins_home/jobs'
              }
            }
          }
      }
    }
} else {
    error 'Run tests manually.'
}
