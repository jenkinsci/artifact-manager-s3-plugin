def name = "test-s3-small-files"
def label = "${name}-${UUID.randomUUID().toString()}"
def uri = "s3://${S3_BUCKET}/${S3_DIR}small-files/"

timestamps {
  podTemplate(name: name, label: label,
    containers: [
      containerTemplate(name: 'aws', image: 'mesosphere/aws-cli', ttyEnabled: true, command: 'cat')
    ]) {

    node(label) {
      stage('Setup') {
        1.upto(100) {
          writeFile file: "test/test-${it}.txt", text: "test ${it}"
        }
      }
      stage('Archive') {
        sh "ls -laFhR"
        archiveArtifacts "test/*"
      }
      stage('Unarchive') {
        dir('unarch') {
          deleteDir()
          unarchive mapping: ["test/": '.']
          sh "ls -laFhR"
        }
      }
      stage('Upload') {
        container('aws') {
//          sh "aws s3 rm ${uri}-uploaded"
          sh "aws s3 sync test/ ${uri}"
        }
      }
      stage('Download') {
        container('aws') {
          sh "aws s3 sync ${uri} test/"
        }
      }
    }
  }
}
