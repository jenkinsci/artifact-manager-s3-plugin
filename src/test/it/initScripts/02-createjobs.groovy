import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob

if (!Boolean.getBoolean("artifact-manager-s3.enabled")) {
    // Production mode, we do not configure the system
    return
}

println("-- Creating Jobs")

if(Jenkins.instance.getItem("big-file") == null) {
    WorkflowJob project1 = Jenkins.instance.createProject(WorkflowJob.class, "big-file")
    project1.definition = new CpsFlowDefinition(
        '''def name = 'test-s3-big-file'
        def file = 'test.bin' 
        timestamps {
         node() {
           try{
             stage("Generating ${file}") {
               sh "[ -f ${file} ] || dd if=/dev/urandom of=${file} bs=10240 count=102400"
             }
             stage('Archive') {
                archiveArtifacts file
              }
              stage('Unarchive') {
                unarchive mapping: ["${file}": 'test.bin']
              }
            } finally {
              deleteDir()
            }
          }
        }
          ''',
        true // Sandbox
    )
    project1.save()
    println("-- ${project1.name} Created")
}

if(Jenkins.instance.getItem("small-files") == null) {
    WorkflowJob project1 = Jenkins.instance.createProject(WorkflowJob.class, "small-files")
    project1.definition = new CpsFlowDefinition(
      '''def name = 'test-s3-small-files'
      timestamps {
       node() {
         try{
           stage("Generating files") {
             for(def i = 1; i < 100; i++) {
               writeFile file: "test/test-${i}.txt", text: "test ${i}"
               }
           }
           stage('Archive') {
              archiveArtifacts 'test/*'
            }
            stage('Unarchive') {
              deleteDir()
              unarchive mapping: ['test/': '.']
            }
          } finally {
            deleteDir()
          }
        }
      }
      ''',
      true // Sandbox
    )
    project1.save()
    println("-- ${project1.name} Created")
}

if(Jenkins.instance.getItem("stash") == null) {
    WorkflowJob project1 = Jenkins.instance.createProject(WorkflowJob.class, "stash")
    project1.definition = new CpsFlowDefinition(
      '''def name = 'test-s3-stash'
      timestamps {
       node() {
         try{
           stage("Generating files") {
             for(def i = 1; i < 100; i++) {
               writeFile file: "test/test-${i}.txt", text: "test ${i}"
               }
           }
           stage('Archive') {
              stash name: 'stuff', includes: 'test/'
            }
            stage('Unarchive') {
              unstash name: 'stuff'
            }
          } finally {
            deleteDir()
          }
        }
      }
      ''',
      true // Sandbox
    )
    project1.save()
    println("-- ${project1.name} Created")
}