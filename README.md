# Artifact Manager on S3 plugin

Artifact manager implementation for Amazon S3, currently using the jClouds library.
[wiki](https://wiki.jenkins.io/display/JENKINS/Artifact+Manager+S3+Plugin)

# Configuration

The plugin is expected to run with a IAM profile and the S3 bucket must be already created.
When running in AWS that means the instance needs to have
an IAM role set with a policy that allows access to the S3 bucket to be used.

This is an example policy

```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Sid": "AllowListingOfFolder",
            "Effect": "Allow",
            "Action": "s3:ListBucket",
            "Resource": "arn:aws:s3:::my-bucket-name",
            "Condition": {
                "StringLike": {
                    "s3:prefix": "some/path/*"
                }
            }
        },
        {
            "Sid": "AllowS3ActionsInFolder",
            "Effect": "Allow",
            "Action": [
                "s3:PutObject",
                "s3:GetObject",
                "s3:DeleteObject",
                "s3:ListObjects"
            ],
            "Resource": "arn:aws:s3:::my-bucket-name/some/path/*"
        }
    ]
}
```

In order to configure the plugin on Jenkins, you have to go to Manage Jenkisn/Configure System to 
the `Artifact Managment for Builds` section, there you have to select the Cloud Provider `Amazon S3`.

![](images/cloud-provider-no-configured.png)

Then you can configure the S3 Bucket settings on the section `Amazon S3 Bucket Access settings` in 
the same configuration page.

* S3 Bucket Name: Name of the S3 Bucket to use to store artifacts.
* S3 Bucket Region: Region to use to generate the URLs to get/put artifacts, by default it is autodetected.
* Base Prefix: Prefix to use for files and folders inside the S3 Bucket, if the prefix is a folder should be end with `/`.

![](images/bucket-settings.png)

# Testing

Pick an AWS profile and region, then create a scratch bucket and choose a subdirectory within it for testing.
Add to your `~/.m2/settings.xml`:

```xml
    <profiles>
        <profile>
            <id>artifact-manager-s3</id>
            <activation>
                <activeByDefault>true</activeByDefault>
            </activation>
            <properties>
                <AWS_PROFILE>…</AWS_PROFILE>
                <AWS_REGION>…</AWS_REGION>
                <S3_BUCKET>…</S3_BUCKET>
                <S3_DIR>…/</S3_DIR>
            </properties>
        </profile>
    </profiles>
```

Now log in to AWS so that `~/.aws/credentials` is up to date with valid credentials, and run:

```bash
mvn clean test
```

For interactive testing, you may instead add to `~/.mavenrc` (cf. comment in MNG-6303):

```sh
export AWS_PROFILE=…
export AWS_REGION=…
```

then:

```bash
mvn hpi:run
```

An example pipeline for testing:

```
timestamps {
  node('uploader') {
    writeFile file: 'f', text: 'some content here'
    archiveArtifacts 'f'
  }
  node('downloader') {
    sh 'ls -l'
    unarchive mapping: [f: 'f']
    sh 'ls -l; cat f'
  }
}
```

You can also install the `log-cli` plugin, and run:

```bash
java -jar jenkins-cli.jar -s http://localhost:8080/jenkins/ tail-log io.jenkins.plugins.artifact_manager_jclouds -l ALL
```

Or to just see HTTP traffic:

```bash
java -jar jenkins-cli.jar -s http://localhost:8080/jenkins/ tail-log org.jclouds.rest.internal.InvokeHttpMethod -l FINE
```

# Running on Agents outside AWS

Create 2 agents in Jenkins, one for uploading and another for downloading, and launch them with Docker

```sh
docker run --rm -i --init -v <YOUR_HOME_DIR>/.aws:/home/jenkins/.aws -e AWS_REGION=… -e AWS_PROFILE=… jenkins/slave:alpine java -jar /usr/share/jenkins/slave.jar
```

but note that the mount will not work if your `~/.aws/` is set to be readable only by the user.

