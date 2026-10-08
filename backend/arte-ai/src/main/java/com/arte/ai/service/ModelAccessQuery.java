package com.arte.ai.service;

import com.arte.ai.pojo.model.ModelConfigDto;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

/** 所有读取和调用入口复用同一权限条件，角色关系实时从数据库读取。 */
public final class ModelAccessQuery {
    private ModelAccessQuery() {}

    public static QueryWrapper<ModelConfigDto> accessible(String userName, boolean includeDisabledOwned) {
        QueryWrapper<ModelConfigDto> query = new QueryWrapper<>();
        if (userName == null || userName.isBlank()) {
            return query.apply("1 = 0");
        }
        query.apply("""
                (create_by = {0} OR (public_flag = 1 AND status = 1 AND (
                    NOT EXISTS (SELECT 1 FROM arte_ai_model_access a WHERE a.model_config_id = arte_ai_model_config.id)
                    OR EXISTS (SELECT 1 FROM arte_ai_model_access a
                        WHERE a.model_config_id = arte_ai_model_config.id AND (
                            (a.subject_type = 'user' AND a.subject = {0})
                            OR (a.subject_type = 'role' AND EXISTS (
                                SELECT 1 FROM arte_rbac_relation r
                                JOIN arte_rbac_role rr_role ON rr_role.role_code = r.target AND rr_role.status = 1
                                WHERE r.binding_type = 'user_to_role'
                                    AND r.source = {0} AND r.target = a.subject))
                        ))
                )))
                """, userName);
        if (!includeDisabledOwned) {
            query.eq("status", 1);
        }
        return query;
    }
}
