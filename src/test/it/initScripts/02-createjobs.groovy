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
        "def name = 'test-s3-big-file'\n" +
        "def file = 'test.bin'\n" + 
        "timestamps {\n" +
        " node() {\n" +
        "   try{\n" +
        "     stage(\"Generating ${file}\") {\n" +
        "       sh \"[ -f ${file} ] || dd if=/dev/urandom of=${file} bs=10240 count=102400\"\n" +
        "     }\n" +
        "     stage('Archive') {\n" +
        "        archiveArtifacts file\n" +
        "      }\n" +
        "      stage('Unarchive') {\n" +
        "        unarchive mapping: [\"${file}\": 'test.bin']\n" +
        "      }\n" +
        "    } finally {\n" +
        "      deleteDir()\n" +
        "    }\n" +
        "  }\n",
        true // Sandbox
    )
    project1.save()
}

if(Jenkins.instance.getItem("small-files") == null) {
    WorkflowJob project1 = Jenkins.instance.createProject(WorkflowJob.class, "small-files")
    project1.definition = new CpsFlowDefinition(
      "def name = 'test-s3-big-file'\n" +
      "def file = 'test.bin'\n" + 
      "timestamps {\n" +
      " node() {\n" +
      "   try{\n" +
      "     stage(\"Generating files\") {\n" +
      "       for(def i = 1; i &lt; 100; i++) {\n" +
      "         writeFile file: \"test/test-${i}.txt\", text: \"test ${i}\"\n" +
      "         }\n" +
      "     }\n" +
      "     stage('Archive') {\n" +
      "        archiveArtifacts 'test/*'\n" +
      "      }\n" +
      "      stage('Unarchive') {\n" +
      "        deleteDir()\n"
      "        unarchive mapping: ['test/': '.']\n"
      "      }\n" +
      "    } finally {\n" +
      "      deleteDir()\n" +
      "    }\n" +
      "  }\n",
      true // Sandbox
    )
    project1.save()
}

if(Jenkins.instance.getItem("stash") == null) {
    WorkflowJob project1 = Jenkins.instance.createProject(WorkflowJob.class, "stash")
    project1.definition = new CpsFlowDefinition(
      "def name = 'test-s3-big-file'\n" +
      "def file = 'test.bin'\n" + 
      "timestamps {\n" +
      " node() {\n" +
      "   try{\n" +
      "     stage(\"Generating files\") {\n" +
      "       for(def i = 1; i &lt; 100; i++) {\n" +
      "         writeFile file: \"test/test-${i}.txt\", text: \"test ${i}\"\n" +
      "         }\n" +
      "     }\n" +
      "     stage('Archive') {\n" +
      "        stash name: 'stuff', includes: 'test/'\n" +
      "      }\n" +
      "      stage('Unarchive') {\n" +
      "        unstash name: 'stuff'\n" +
      "      }\n" +
      "    } finally {\n" +
      "      deleteDir()\n" +
      "    }\n" +
      "  }\n",
      true // Sandbox
    )
    project1.save()
}