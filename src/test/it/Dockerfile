FROM jenkins/jenkins:2.121.1
USER root
COPY jenkins.war /usr/share/jenkins/jenkins.war
COPY jenkins_home/ /var/jenkins_home/
COPY initScripts/enableArtifactManager.groovy /var/jenkins_home/init.groovy.d/enableArtifactManager.groovy
RUN chown -R jenkins:jenkins /var/jenkins_home/
USER jenkins