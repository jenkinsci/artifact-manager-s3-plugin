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
def yamlDinD = readFile file: baseDir + "/dind-agent.yml"
yamlDinD.replaceAll("<NAME>",name)

    timestamps {
      podTemplate(label: label, yaml: yamlDinD) {
          node(label){
            stage('Build Docker Image'){
                infra.checkout()
                dir(baseDir) {
                  unarchive mapping: ["jenkins-war-2.121-artifact-manager-s3-SNAPSHOT.war": "jenkins.war"]
                  container('docker'){
                      docker.withRegistry('https://docker.cloudbees.com', '80ca7cb9-b576-43df-9f54-ac49882dd7a9') {
                          def customImage = docker.build("${name}:${env.BUILD_ID}")
                          customImage.push()
                      }
                  }
                }
              }
            }
          }
    }

label = "${name}-${UUID.randomUUID().toString()}"
def yaml = readFile file: baseDir + "/jenkins.yml"
yaml.replaceAll("<NAME>",name)
yaml.replaceAll("<BUILD_ID>",env.BUILD_ID)

    timestamps {
      podTemplate(label: label, yaml: yaml){
          node(label) {
            stage('Run on k8s'){
              container(name) {
                sh 'sh /var/jenkins_home/runJobs.sh' 
              }
            }
          }
      }
    }
} else {
    error 'Run tests manually.'
}
