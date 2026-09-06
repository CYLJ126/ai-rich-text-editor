SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS initialize_arte_public_docs;
DELIMITER $$
CREATE PROCEDURE initialize_arte_public_docs()
BEGIN
    DECLARE intro_md LONGTEXT;
    DECLARE features_md LONGTEXT;

    SET intro_md = CONVERT(LOAD_FILE('/var/lib/mysql-files/arte-intro.md') USING utf8mb4);
    SET features_md = CONVERT(LOAD_FILE('/var/lib/mysql-files/arte-features.md') USING utf8mb4);

    IF intro_md IS NULL OR features_md IS NULL THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'ARTE public documentation files are missing from /var/lib/mysql-files';
    END IF;

    INSERT IGNORE INTO arte_rt_catalog
        (id, name, father_id, order_id, description, create_by, update_by, is_public, is_delete)
    VALUES
        (118, 'ARTE 使用指南', NULL, 1, 'ARTE 产品介绍与完整功能手册', 'admin', 'admin', 1, 0);

    INSERT IGNORE INTO arte_rt_article
        (id, author, title, summary, cover, catalog_id, content_json, content_md, content_text,
         is_delete, is_public, create_by, update_by, character_count, order_id,
         access_level, article_type, row_version)
    VALUES
        (118, 'admin', '118-ARTE简介',
         'ARTE 的产品定位、核心体验、典型使用场景与私有部署价值。',
         '/arte/article-covers/connected-knowledge.webp', 118, NULL, intro_md, intro_md,
         0, 1, 'admin', 'admin', CHAR_LENGTH(intro_md), 1,
         'private', 'generic', 1),
        (119, 'admin', '119-ARTE全部特性',
         'ARTE 写作、图表、媒体、搜索、AI、批注、版本与管理能力的完整使用手册。',
         '/arte/article-covers/structured-space.webp', 118, NULL, features_md, features_md,
         0, 1, 'admin', 'admin', CHAR_LENGTH(features_md), 2,
         'private', 'generic', 1);
END$$
DELIMITER ;

CALL initialize_arte_public_docs();
DROP PROCEDURE IF EXISTS initialize_arte_public_docs;
