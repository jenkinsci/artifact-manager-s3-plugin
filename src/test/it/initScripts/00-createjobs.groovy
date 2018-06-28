import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob

if (!Boolean.getBoolean("artifact-manager-s3.enabled")) {
    // Production mode, we do not configure the system
    return
}

println("-- Creating Jobs")

if(Jenkins.instance.getItem("big-file") == null) {
    WorkflowJob project1 = Jenkins.instance.createProject(WorkflowJob.class, "ig-file")
    project1.definition = new CpsFlowDefinition(
        "def name = 'test-s3-big-file'" +
        "def file = 'test.bin'" + 
        "timestamps {" +
        " node() {" +
        "   try{" +
        "     stage(\"Generating ${file}\") {" +
        "       sh '[ -f ${file} ] || dd if=/dev/urandom of=${file} bs=10240 count=102400'" +
        "     }" +
        "     stage('Archive') {" +
        "        archiveArtifacts file" +
        "      }" +
        "      stage('Unarchive') {" +
        "        unarchive mapping: ['${file}': 'test.bin']" +
        "      }" +
        "    } finally {" +
        "      deleteDir()" +
        "    }" +
        "  }",
        true // Sandbox
    )
    project1.save()
}

if(Jenkins.instance.getItem("small-files") == null) {
    WorkflowJob project1 = Jenkins.instance.createProject(WorkflowJob.class, "small-files")
    project1.definition = new CpsFlowDefinition(
        "",
        true // Sandbox
    )
    project1.save()
}

if(Jenkins.instance.getItem("stash") == null) {
    WorkflowJob project1 = Jenkins.instance.createProject(WorkflowJob.class, "stash")
    project1.definition = new CpsFlowDefinition(
        "",
        true // Sandbox
    )
    project1.save()
}