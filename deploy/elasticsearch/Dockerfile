FROM elasticsearch:8.13.4

LABEL org.opencontainers.image.source="https://github.com/CYLJ126/ai-rich-text-editor" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.title="AIRichTextEditor Elasticsearch"

RUN bin/elasticsearch-plugin install --batch https://get.infini.cloud/elasticsearch/analysis-ik/8.13.4
