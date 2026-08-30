package com.nip.app.pojo.richtext;

/**
 * 文章 Elasticsearch 全量重建结果。
 */
public record ArticleEsReindexResult(int total, int succeeded, int failed) {
}
