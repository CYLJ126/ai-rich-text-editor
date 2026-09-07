FROM node:22-alpine AS build
WORKDIR /workspace/frontend

ARG BUILD_PROXY_URL
ARG BUILD_NPM_REGISTRY=https://registry.npmjs.org
ARG TARGETARCH

COPY frontend/package.json frontend/yarn.lock ./
RUN --mount=type=cache,target=/root/.npm \
    if [ -n "$BUILD_PROXY_URL" ]; then \
      export HTTP_PROXY="$BUILD_PROXY_URL" HTTPS_PROXY="$BUILD_PROXY_URL"; \
    fi \
    && npm install --global --force yarn@1.22.22 --registry="$BUILD_NPM_REGISTRY"
# Isolate architecture builds and serialize writers to each Yarn v1 cache.
RUN --mount=type=cache,id=arte-frontend-yarn-${TARGETARCH},target=/usr/local/share/.cache/yarn,sharing=locked \
    yarn install --frozen-lockfile --ignore-scripts

COPY frontend/ ./
ENV NODE_OPTIONS=--max-old-space-size=4096
RUN npm run build

FROM nginx:1.27-alpine
LABEL org.opencontainers.image.source="https://github.com/CYLJ126/ai-rich-text-editor" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.title="AIRichTextEditor Frontend"

COPY deploy/frontend/nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /workspace/frontend/dist/ /usr/share/nginx/html/

EXPOSE 8080 8443
HEALTHCHECK --interval=10s --timeout=5s --retries=10 \
  CMD wget -q --spider http://127.0.0.1:8080/ || exit 1
