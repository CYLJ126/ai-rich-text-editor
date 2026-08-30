package com.nip.app.common.constant;

/**
 * TODO
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/14 10:15 ✾
 **/
public class RichTextEsIndexConstants {
    private RichTextEsIndexConstants() {
    }

    // 索引名
    public static final String DOCUMENT_INDEX = "articles";
    public static final String CHUNK_INDEX = "article_chunks";
    // 文档字段
    public static final String FIELD_ARTICLE_ID = "article_id";
    public static final String FIELD_TITLE = "title";
    public static final String FIELD_TITLE_KW = "title.keyword";
    public static final String FIELD_SUMMARY = "summary";
    public static final String FIELD_AUTHOR = "author";
    public static final String FIELD_CATALOG_ID = "catalog_id";
    public static final String FIELD_TAG_IDS = "tag_ids";
    public static final String FIELD_TAG_NAMES = "tag_names";
    public static final String FIELD_IS_PUBLIC = "is_public";
    public static final String FIELD_IS_DELETE = "is_delete";
    public static final String FIELD_ACCESS_LEVEL = "access_level";
    public static final String FIELD_ROW_VERSION = "row_version";
    public static final String FIELD_CREATE_TIME = "create_time";
    public static final String FIELD_UPDATE_TIME = "update_time";
    // 分块字段
    public static final String FIELD_CHUNK_ID = "chunk_id";
    public static final String FIELD_CONTENT = "content";
    public static final String FIELD_SECTION_HEADING = "section_heading";
    public static final String FIELD_CONTENT_BREADCRUMB = "content_with_breadcrumb";
    public static final String FIELD_BREADCRUMB = "breadcrumb";
    public static final String FIELD_EMBEDDING = "embedding";
    public static final String FIELD_BOLD_TERMS = "bold_terms";
    public static final String FIELD_HIGHLIGHT_TERMS = "highlight_terms";
    public static final String FIELD_HAS_STRIKETHROUGH = "has_strikethrough";
    public static final String FIELD_CHUNK_INDEX = "chunk_index";
    public static final String FIELD_CHUNK_TYPE = "chunk_type";
    public static final String FIELD_ARTICLE_META = "article_meta";
    public static final String FIELD_ARTICLE_META_TITLE = FIELD_ARTICLE_META + "." + FIELD_TITLE;
    public static final String FIELD_ARTICLE_META_IS_PUBLIC = FIELD_ARTICLE_META + "." + FIELD_IS_PUBLIC;
    // kNN 参数默认值
    public static final int DEFAULT_KNN_K = 10;
    public static final int DEFAULT_KNN_NUM_CANDIDATES = 100;
    public static final int DEFAULT_EMBEDDING_DIMS = 1536;
    // 分页默认值
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
}
