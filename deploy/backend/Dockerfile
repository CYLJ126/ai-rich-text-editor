FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace/backend

ARG BUILD_PROXY_HOST
ARG BUILD_PROXY_PORT=3128

COPY backend/ ./
RUN --mount=type=cache,target=/root/.m2 \
    cp profile/app.properties.example profile/app.properties \
    && if [ -n "$BUILD_PROXY_HOST" ]; then \
         export MAVEN_OPTS="-Dhttp.proxyHost=$BUILD_PROXY_HOST -Dhttp.proxyPort=$BUILD_PROXY_PORT -Dhttps.proxyHost=$BUILD_PROXY_HOST -Dhttps.proxyPort=$BUILD_PROXY_PORT"; \
       fi \
    && mvn -B -ntp -pl arte-app -am package -DskipTests

COPY deploy/backend/DeploymentSecrets.java /workspace/DeploymentSecrets.java
RUN --mount=type=cache,target=/root/.m2 \
    mkdir -p /workspace/bootstrap \
    && cp /root/.m2/repository/org/bouncycastle/bcprov-jdk18on/1.77/bcprov-jdk18on-1.77.jar /workspace/bootstrap/ \
    && javac -cp '/workspace/bootstrap/*' -d /workspace/bootstrap /workspace/DeploymentSecrets.java

FROM eclipse-temurin:21-jre-noble
WORKDIR /app

LABEL org.opencontainers.image.source="https://github.com/CYLJ126/ai-rich-text-editor" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.title="AIRichTextEditor Backend"

RUN groupadd --gid 10001 arte \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin arte \
    && mkdir -p /app/config /app/secrets /app/data/uploads /usr/local/data/log/AIRichTextEditor \
    && chown -R arte:arte /app /usr/local/data/log

COPY --from=build --chown=arte:arte /workspace/backend/arte-app/target/arte-app-boot.jar /app/arte-app-boot.jar
COPY --from=build /workspace/bootstrap/ /app/bootstrap/
COPY --chown=arte:arte deploy/backend/docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh

USER arte
EXPOSE 12636

ENTRYPOINT ["/app/docker-entrypoint.sh"]
