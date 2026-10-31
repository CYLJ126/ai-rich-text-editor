package com.arte.app.platform.persistence.mapper;

import org.apache.ibatis.annotations.Param;

/**
 * Explicit SQL on appDataSource; payloads containing encrypted keys never leave this adapter.
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 15:55 ✾
 */
public interface PlatformModelDraftMapper {

    record Row(long version, String payload) {
    }

    Row draft(@Param("key") String key);

    int insertDraft(@Param("key") String key, @Param("version") long version, @Param("payload") String payload);

    int replaceDraft(@Param("key") String key, @Param("expected") long expected, @Param("version") long version, @Param("payload") String payload);

    Row credential(@Param("key") String key);

    int insertCredential(@Param("key") String key, @Param("version") long version, @Param("payload") String payload);

    int replaceCredential(@Param("key") String key, @Param("expected") long expected, @Param("version") long version, @Param("payload") String payload);

    Row validation(@Param("id") String id);

    int insertValidation(@Param("id") String id, @Param("key") String key, @Param("version") long version, @Param("payload") String payload);

    int replaceValidation(@Param("id") String id, @Param("expected") long expected, @Param("version") long version, @Param("payload") String payload);
}
