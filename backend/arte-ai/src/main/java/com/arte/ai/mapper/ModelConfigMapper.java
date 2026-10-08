package com.arte.ai.mapper;

import com.arte.ai.pojo.model.ModelConfigDto;
import com.arte.ai.pojo.model.ModelAccessGrant;
import com.arte.core.annotations.MybatisParams;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;

import java.util.List;
import java.util.Map;

/**
 * <p>
 * 模型配置表 Mapper 接口
 * </p>
 *
 * @author zhangsc
 * @since 2026-06-13
 */
@MybatisParams(value = "arte_ai_message", queryFields = {})
public interface ModelConfigMapper extends BaseMapper<ModelConfigDto> {
    /**
     * 清除同一 modelType 下所有默认标记
     *
     * @param modelType 模型类型
     */
    void clearDefaultByModelType(@Param("modelType") String modelType);

    /**
     * 批量更新排序
     *
     * @param sortList 排序列表
     */
    void batchUpdateSort(@Param("sortList") List<ModelConfigDto> sortList);

    @Select("SELECT subject_type, subject FROM arte_ai_model_access WHERE model_config_id = #{id}")
    List<ModelAccessGrant> selectAccessGrants(@Param("id") Integer id);

    @Delete("DELETE FROM arte_ai_model_access WHERE model_config_id = #{id}")
    int deleteAccessGrants(@Param("id") Integer id);

    @Insert("INSERT INTO arte_ai_model_access (model_config_id, subject_type, subject) VALUES (#{id}, #{type}, #{subject})")
    int insertAccessGrant(@Param("id") Integer id, @Param("type") String type, @Param("subject") String subject);

    @Select("SELECT name AS label, name AS value FROM arte_rbac_user WHERE status = 1 ORDER BY name")
    List<Map<String, String>> selectAccessUsers();

    @Select("SELECT role_name AS label, role_code AS value FROM arte_rbac_role WHERE status = 1 ORDER BY role_code")
    List<Map<String, String>> selectAccessRoles();
}
