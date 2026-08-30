FROM eclipse-temurin:21-jre-noble
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends libstdc++6 fontconfig fonts-dejavu-core zip \
    && rm -rf /var/lib/apt/lists/*

ARG ARTE_APP_JAR_FILE
COPY ${ARTE_APP_JAR_FILE} /app/arte-app-boot.jar
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh

EXPOSE 12636

ENTRYPOINT ["/app/docker-entrypoint.sh"]
