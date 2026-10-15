#! /bin/sh
cd /usr/local/src/script || exit 1
BASE_PATH='/usr/local/src/tianji/tjxt'
PROJECT_NAME=""
PROJECT_PATH=''
CONTAINER_NAME=""
JAVA_OPTS="-Xms300m -Xmx300m"
PORT=8080
DEBUG_PORT=0
SKYWALKING_AGENT_DIR="${SKYWALKING_AGENT_DIR:-/usr/local/src/skywalking-agent}"
SKYWALKING_COLLECTOR="${SKYWALKING_COLLECTOR:-192.168.150.101:11800}"
while getopts "c:n:d:p:o:a:" opt; do
    case $opt in
         c)
            CONTAINER_NAME=$OPTARG
          ;;
         n)
            PROJECT_NAME=$OPTARG
          ;;
         d)
            PROJECT_PATH=$OPTARG
          ;;
         p)
            PORT=$OPTARG
          ;;
         o)
            [ -n "$OPTARG" ] && JAVA_OPTS=$OPTARG
          ;;
         a)
            [ -n "$OPTARG" ] && DEBUG_PORT=$OPTARG
          ;;
         ?)
            echo "unkonw argument"
            exit 1
          ;;
    esac
done
if [ "$DEBUG_PORT" = "0" ]; then
  JAVA_OPTS=$JAVA_OPTS
else
  JAVA_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
fi

SKYWALKING_ENABLED=0
if [ -f "${SKYWALKING_AGENT_DIR}/skywalking-agent.jar" ]; then
  SKYWALKING_ENABLED=1
  echo "SkyWalking agent enabled: service=${PROJECT_NAME}, collector=${SKYWALKING_COLLECTOR}"
else
  echo "SkyWalking agent not found at ${SKYWALKING_AGENT_DIR}; continue without agent"
fi

IMAGE_NAME="${CONTAINER_NAME}:latest"
echo "copy xx.jar from ${BASE_PATH}/${PROJECT_PATH}"
rm -f app.jar
cp ${BASE_PATH}/${PROJECT_PATH}/target/${PROJECT_NAME}.jar ./app.jar ||  exit 1

echo "begin to build ${PROJECT_NAME} image ！！"

[ -n "`docker ps -a | grep ${CONTAINER_NAME}`" ] && docker rm -f ${CONTAINER_NAME}
[ -n "`docker images | grep ${CONTAINER_NAME}`" ] && docker rmi ${IMAGE_NAME}

docker build -t ${IMAGE_NAME} . || exit 1
echo "${PROJECT_NAME} image build success，java_opts = $JAVA_OPTS ！！^_^"

echo "begin to create container ${CONTAINER_NAME}，port: ${PORT} ！！"

run_container() {
  if [ "$DEBUG_PORT" = "0" ]; then
    echo "run in normal mode"
    set -- docker run -d --name "$CONTAINER_NAME" \
      -p "${PORT}:${PORT}" \
      --restart=always \
      -e "JAVA_OPTS=${JAVA_OPTS}" \
      -e SPRING_PROFILES_ACTIVE=test
  else
    echo "run in debug mode"
    set -- docker run -d --name "$CONTAINER_NAME" \
      -p "${PORT}:${PORT}" \
      --restart=always \
      -p "${DEBUG_PORT}:5005" \
      -e "JAVA_OPTS=${JAVA_OPTS}" \
      -e SPRING_PROFILES_ACTIVE=test
  fi

  if [ "$SKYWALKING_ENABLED" = "1" ]; then
    set -- "$@" \
      -v "${SKYWALKING_AGENT_DIR}:/opt/skywalking-agent:ro" \
      -e JAVA_TOOL_OPTIONS=-javaagent:/opt/skywalking-agent/skywalking-agent.jar \
      -e "SW_AGENT_NAME=${PROJECT_NAME}" \
      -e "SW_AGENT_COLLECTOR_BACKEND_SERVICES=${SKYWALKING_COLLECTOR}" \
      -e SW_LOGGING_OUTPUT=CONSOLE
  fi

  set -- "$@" "$IMAGE_NAME"
  "$@" || exit 1
}

run_container

if [ "$SKYWALKING_ENABLED" = "1" ]; then
  echo "SkyWalking is attached to ${PROJECT_NAME}"
fi
echo "container is running now !! ^_^"
exit 0
