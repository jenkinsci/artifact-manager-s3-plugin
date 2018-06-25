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
    timestamps {
      podTemplate(label: label) {
        node(label){
          stage ('Prepare environment'){
            env.labelDind = "${name}-${UUID.randomUUID().toString()}"
            env.baseDir = "src/test/it"
            env.yamlDinD = readFile file: baseDir + "/dind-agent.yml"
            env.yamlDinD.replaceAll("<NAME>",name)

            env.labelJenkins = "${name}-${UUID.randomUUID().toString()}"
            env.yaml = readFile file: baseDir + "/jenkins.yml"
            env.yaml.replaceAll("<NAME>",name)
            env.yaml.replaceAll("<BUILD_ID>",env.BUILD_ID)  
          }  
        }
      }
    }
    
    timestamps {
      podTemplate(label: labelDind, yaml: yamlDinD) {
          node(labelDind){
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

    timestamps {
      podTemplate(label: labelJenkins, yaml: yaml){
          node(labelJenkins) {
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
