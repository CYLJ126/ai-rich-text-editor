FROM nginx:1.27-alpine

COPY nginx.conf.template /etc/nginx/templates/default.conf.template

RUN apk add openssl unzip \
    && mkdir -p /etc/nginx/certs \
    && openssl req -x509 -nodes -newkey rsa:2048 -days 36500 \
        -keyout /etc/nginx/certs/server.key \
        -out /etc/nginx/certs/server.crt \
        -subj "/CN=localhost" \
    && rm -rf /usr/share/nginx/html/*

ARG ARTE_FRONT_DIST_FILE
COPY ${ARTE_FRONT_DIST_FILE} /tmp/dist.zip
RUN unzip /tmp/dist.zip -d /usr/share/nginx/html \
    && rm /tmp/dist.zip

EXPOSE 8000
