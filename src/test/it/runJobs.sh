#!/bin/sh
buildJob(){
  local job=${1:-?}
  echo $(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/job/${job}/build?delay=0sec)
}

waitFinishJob(){
  local job=${1:-?}
  RUNNING="true"
  while [ "${RUNNING}" = "true" ]
  do
    sleep 10
    RUNNING="$(curl -sS http://127.0.0.1:8080/job/${job}/1/api/xml?xpath=/workflowRun/building/text\(\))"
    echo -n "."
  done                     
}

getResultJob(){
  local job=${1:-?}
  echo "$(curl -sS http://127.0.0.1:8080/job/${job}/1/api/xml?xpath=/workflowRun/result/text\(\))"
}

getDurationJob(){
  local job=${1:-?}
  echo "$(curl -sS http://127.0.0.1:8080/job/${job}/1/api/xml?xpath=/workflowRun/duration)"
}

deleteJob(){
  local job=${1:-?}
  echo "$(curl -X POST -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/job/${job}/doDelete)"
}

downloadArtifacts(){
  local job=${1:-?}
  echo "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/job/${job}/1/artifact/*zip*/archive.zip)"
}

waitForJenkinsUpAndRunning(){
  local STATUS="$(curl -sS http://127.0.0.1:8080/api/xml?xpath=/hudson/mode/text\(\))"
  while [ "${STATUS}" != "NORMAL" ]
  do
    sleep 10
    STATUS="$(curl -sS http://127.0.0.1:8080/api/xml?xpath=/hudson/mode/text\(\))"
    echo -n "."
  done
}

echo "Waiting for Jenkins up and running"
waitForJenkinsUpAndRunning

echo "Build jobs"
echo "Big-file - $(buildJob big-file)"
echo "Small files - $(buildJob small-files)"
echo "Stash - $(buildJob stash)"

echo "Waiting for jobs finished"
waitFinishJob stash
waitFinishJob small-files
waitFinishJob big-file

echo "Job Results"
RESULT_BIGFILE=$(getResultJob big-file)
RESULT_SMALFILES=$(getResultJob small-files)
RETURL_STASH=$(getResultJob stash)

echo "RESULT_BIGFILE=${RESULT_BIGFILE} - $(getDurationJob big-file)"
echo "RESULT_SMALFILES=${RESULT_SMALFILES} - $(getDurationJob small-files)"
echo "RETURL_STASH=${RETURL_STASH} - $(getDurationJob stash)"

echo "Check results"
[ "${RESULT_BIGFILE}" = "SUCCESS" ] || exit 1
[ "${RESULT_SMALFILES}" = "SUCCESS" ] || exit 1
[ "${RETURL_STASH}" = "SUCCESS" ] || exit 1

echo "Download Artifact"
RESULT_DOWNLOAD=$(downloadArtifacts small-files)
[ "${RESULT_DOWNLOAD}" -lt "400" ] || exit 1

echo "Delete jobs and artifacts"
echo "Big-file - $(deleteJob big-file)"
echo "Small-files - $(deleteJob small-files)"
echo "Stash - $(deleteJob stash)"



