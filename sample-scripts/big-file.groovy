def name = "test-s3-big-file"
def label = "${name}-${UUID.randomUUID().toString()}"
def file = "CentOS-7-x86_64-DVD-1505-01.iso"
def uri = "s3://${S3_BUCKET}/${S3_DIR}${file}"

timestamps {
  podTemplate(name: name, label: label,
    containers: [
      containerTemplate(name: 'aws', image: 'mesosphere/aws-cli', ttyEnabled: true, command: 'cat')
    ]) {

    node(label) {
      stage('CLI Download') {
        container('aws') {
          sh "aws s3 cp ${uri} ."
        }
      }
      sh "ls -laFh"
      stage('Archive') {
        archiveArtifacts file
      }
      sh "ls -laFh"
      stage('Unarchive') {
        unarchive mapping: ["${file}": 'CentOS-7-x86_64-DVD-1505-01-unarchived.iso']
      }
      sh "ls -laFh"
      container('aws') {
        sh "aws s3 rm ${uri}-uploaded"
      }
      stage('CLI Upload') {
        container('aws') {
          sh "aws s3 cp ${file} ${uri}-uploaded"
        }
      }
    }
  }
}
