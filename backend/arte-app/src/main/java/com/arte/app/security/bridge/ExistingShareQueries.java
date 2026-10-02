package com.arte.app.security.bridge;

import com.arte.app.mapper.richtext.ShareMapper;
import com.arte.app.pojo.richtext.ShareDto;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 复用原 Mapper 的递归分享 SQL，并用 JDBC 执行。避免旧 ThreadLocal 拦截器以及
 * MyBatis 会话／二级缓存让同一事务内的再次授权读到先前的允许结果。
 */
public class ExistingShareQueries {
    private final JdbcTemplate jdbc;
    private final SqlSessionFactory mybatis;

    public ExistingShareQueries(JdbcTemplate jdbc, SqlSessionFactory mybatis) {
        this.jdbc = jdbc;
        this.mybatis = mybatis;
    }

    List<ShareDto> article(int id, Integer catalogId, String userName) {
        Map<String, Object> params = new HashMap<>();
        params.put("articleId", id);
        params.put("catalogId", catalogId);
        params.put("targetUser", userName);
        return query("listEffectiveArticleShares", params);
    }

    List<ShareDto> catalog(int id, String userName) {
        return query("listEffectiveCatalogShares", Map.of("catalogId", id, "targetUser", userName));
    }

    private List<ShareDto> query(String method, Map<String, Object> parameters) {
        var statement = mybatis.getConfiguration().getMappedStatement(ShareMapper.class.getName() + "." + method);
        var sql = statement.getBoundSql(parameters);
        Object[] arguments = sql.getParameterMappings().stream().map(mapping -> sql.hasAdditionalParameter(mapping.getProperty())
                ? sql.getAdditionalParameter(mapping.getProperty()) : parameters.get(mapping.getProperty())).toArray();
        return jdbc.query(sql.getSql(), (rs, row) -> {
            var share = new ShareDto();
            share.setId(rs.getInt("id"));
            share.setResourceType(rs.getString("resource_type"));
            share.setResourceId(rs.getInt("resource_id"));
            share.setTargetType(rs.getString("target_type"));
            share.setTargetUser(rs.getString("target_user"));
            share.setTargetRole(rs.getString("target_role"));
            share.setPermission(rs.getString("permission"));
            share.setArticlePermission(rs.getString("article_permission"));
            return share;
        }, arguments);
    }
}
