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
      podTemplate(label: label, containers: [
        containerTemplate(name: 'jnlp', image: 'jenkins/jnlp-slave', args: '${computer.jnlpmac} ${computer.name}')
        ]) {
        node(label){
          stage ('Prepare environment'){
            infra.checkout()
            env.labelDind = "${name}-${UUID.randomUUID().toString()}"
            env.baseDir = "src/test/it"
            env.yamlDinD = readFile file: baseDir + "/dind-agent.yml"
            env.yamlDinD = env.yamlDinD.replaceAll("<NAME>",name)

            env.labelJenkins = "${name}-${UUID.randomUUID().toString()}"
            env.yamlJenkins = readFile file: baseDir + "/jenkins.yml"
            env.yamlJenkins = env.yamlJenkins.replaceAll("<NAME>",name)
            env.yamlJenkins = env.yamlJenkins.replaceAll("<BUILD_ID>",env.BUILD_ID)  
          }  
        }
      }
    }
    
    timestamps {
      podTemplate(label: env.labelDind, yaml: env.yamlDinD) {
          node(env.labelDind){
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
      podTemplate(label: env.labelJenkins, yaml: env.yamlJenkins){
          node(env.labelJenkins) {
            stage('Run on k8s'){
              container(name) {
                try {
                  sh 'sh /var/jenkins_home/runJobs.sh' 
                } catch (e) {
                  sh 'cp -R /var/jenkins_home/jobs .'
                  archiveArtifacts "jobs"
                  throw e
                }
            }
          }
        }
      }
    }
} else {
    error 'Run tests manually.'
}
