def name = "test-s3-stash"
def label = "${name}-${UUID.randomUUID().toString()}"

timestamps {
  podTemplate(name: name, label: label,
    containers: [
      containerTemplate(name: 'aws', image: 'mesosphere/aws-cli', ttyEnabled: true, command: 'cat')
    ]) {

    node(label) {
        writeFile file: 'dir/stuff.txt', text: 'hello'
        writeFile file: 'temp', text: BUILD_ID
        archiveArtifacts 'dir/'
        stash name: 'stuff', includes: 'temp'
    }
    node(label) {
        dir('unarch') {
          unarchive mapping: [temp: 'temp']
        }
        dir('unst') {
            unstash 'stuff'
        }
    }

  }
}
