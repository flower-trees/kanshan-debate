FROM registry.cn-hangzhou.aliyuncs.com/salt-x/openjdk:17
LABEL authors="salt-x"

ENV TimeZone=Asia/Shanghai

RUN ln -snf /usr/share/zoneinfo/$TimeZone /etc/localtime && echo $TimeZone > /etc/timezone

ARG PARAM_APP_NAME=kanshan-debate
#ARG 1

ENV APP_NAME=${PARAM_APP_NAME}
#ENV ENVIRONMENT=${PARAM_ENVIRONMENT}

RUN echo ${APP_NAME}
#RUN echo ${ENVIRONMENT}

ADD ./${APP_NAME}/target/${APP_NAME}-1.0.0-SNAPSHOT.jar /app/${APP_NAME}.jar

WORKDIR /app

EXPOSE 8080

# 生成 entrypoint.sh 脚本
RUN echo '#!/bin/sh' > /entrypoint.sh && \
    echo 'exec java -Dspring.profiles.active=$ENVIRONMENT -jar ${APP_NAME}.jar' >> /entrypoint.sh && \
    chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]
