FROM jenkins/jenkins:2.121.1
USER root
COPY jenkins.war /usr/share/jenkins/jenkins.war
COPY jenkins_home /usr/share/jenkins/ref
COPY initScripts/enableArtifactManager.groovy /usr/share/jenkins/ref/init.groovy.d/enableArtifactManager.groovy
USER jenkins
