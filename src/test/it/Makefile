# Just a Makefile for manual testing
.PHONY: all

ARTIFACT_ID = jenkins-war
VERSION = 2.121-artifact-manager-s3-SNAPSHOT
CWP_VERSION = 0.1-alpha-6-20180524.212000-3

all: clean build

clean:
	rm -rf tmp

build: tmp/output/target/${ARTIFACT_ID}-${VERSION}.war

tmp/output/target/${ARTIFACT_ID}-${VERSION}.war:
	mvn io.jenkins.tools.custom-war-packager:custom-war-packager-maven-plugin:${CWP_VERSION}:build \
	    --errors \
	    -Denvironment=aws \
	    -DconfigFile=essentials.yml -Dversion=${VERSION} -DbomFile=bom.yml

run: tmp/output/target/${ARTIFACT_ID}-${VERSION}.war
	JENKINS_HOME=work java \
	    -Dartifact-manager-s3.enabled=true \
	    -jar tmp/output/target/${ARTIFACT_ID}-${VERSION}.war \
        --httpPort=8080 --prefix=/jenkins

pct: tmp/output/target/${ARTIFACT_ID}-${VERSION}.war
	docker run --rm \
	    -v ${HOME}/.m2:/root/.m2 \
	    -v $(shell pwd)/pct_out:/pct/out \
	    -v $(shell pwd)/pct_tmp:/pct/tmp \
		-v $(shell pwd)/tmp/output/target/${ARTIFACT_ID}-${VERSION}.war:/pct/jenkins.war:ro \
		-e ARTIFACT_ID=artifact-manager-s3 \
		-e INSTALL_BUNDLED_SNAPSHOTS=true \
		jenkins/pct \
		-mavenProperties jenkins-test-harness.version=2.38:jth.jenkins-war.path=/pct/jenkins.war

