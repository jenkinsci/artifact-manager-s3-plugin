#!/bin/sh
buildJob(){
  local job=${1:-?}
  curl http://127.0.0.1:8080/job/${job}/build?delay=0sec
}

waitFinishJob(){
  local job=${1:-?}
  RUNNING="<building>true</building>"
  while [ ${RUNNING} = "<building>true</building>" ] 
  do
    RUNNING=$(curl -sS http://127.0.0.1:8080/job/${job}//1/api/xml?xpath=/workfwRun/building)
  done                     
}

getResultJob(){
  local job=${1:-?}
  return $(curl -sS http://127.0.0.1:8080/job/${job}/1/api/xml?xpath=/workflowRun/result)
}

getDurationJob(){
  local job=${1:-?}
  return $(curl -sS http://127.0.0.1:8080/job/${job}/1/api/xml?xpath=/workflowRun/duration)
}

deleteJob(){
  local job=${1:-?}
  return $(curl -sS http://127.0.0.1:8080/job/${job}/doDelete)
}

downloadArtifacts(){
  local job=${1:-?}
  return $(curl http://127.0.0.1:8080/job/${job}/1/artifact/*zip*/archive.zip)
}

echo "Build jobs"
buildJob big-file
buildJob small-files
buildJob stash

echo "Waiting for jobs finished"
waitFinishJob stash
waitFinishJob small-files
waitFinishJob big-file

echo "Job Results"
RESULT_BIGFILE=$(getResultJob big-file)
RESULT_SMALFILES=$(getResultJob small-files)
RETURL_STASH$(getResultJob stash)

echo "RESULT_BIGFILE=${RESULT_BIGFILE} - $(getDurationJob big-file)"
echo "RESULT_SMALFILES=${RESULT_SMALFILES} - $(getDurationJob small-files)"
echo "RETURL_STASH=${RETURL_STASH} - $(getDurationJob stash)"

echo "Download Artifact"
downloadArtifacts small-files

echo "Delete jobs and artifacts"
deleteJob big-file
deleteJob small-files
deleteJob stash

echo "Check results"
[[ ${RESULT_BIGFILE} == "<result>SUCCESS</result>" ]] || exit -1
[[ ${RESULT_SMALFILES} == "<result>SUCCESS</result>" ]] || exit -1
[[ ${RETURL_STASH} == "<result>SUCCESS</result>" ]] || exit -1

