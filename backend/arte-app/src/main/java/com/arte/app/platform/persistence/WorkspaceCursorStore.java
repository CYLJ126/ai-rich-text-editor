package com.arte.app.platform.persistence;

import com.arte.core.enums.workspace.TenantTypeEnum;
import com.arte.core.pojo.workspace.WorkspaceCandidateKey;
import com.arte.core.utils.platform.PlatformChecks;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 租户与空间目录公开游标的存储接口
 * 用于跨请求、跨应用实例共享有有效期的导航状态。
 * <p>
 * 实现负责生成不透明令牌、保存游标和检查有效性；调用方负责验证主体及查询条件的绑定关系。
 * 游标只包含扫描位置和查询上下文，不保存空间摘要或授权决定，内部候选扫描无需依赖此存储。
 */
public interface WorkspaceCursorStore {

    /**
     * 公开游标对应的导航状态，构造时校验版本、扫描边界和规范化查询条件。
     *
     * @param subjectId 游标所属认证主体的标识，继续浏览时必须与当前主体一致
     * @param version   游标结构版本，当前仅支持版本 3
     * @param size      绑定的单次结果条数上限，范围为 1～100，继续浏览时不可改变
     * @param search    绑定的规范化搜索词，不能为空引用；去除首尾空白后按 Locale.ROOT 转为小写，
     *                  不含控制字符，空字符串表示不筛选名称；允许最多 300 个 UTF-16 代码单元以容纳原始搜索词转小写后的长度增长
     * @param key       最后一个已处理候选的不可变排序边界，包含创建时间和租户、空间标识；继续扫描不依赖该候选仍然存在
     * @param tenantId  可选租户过滤，旧的无筛选游标不含此字段
     */
    record Cursor(String subjectId, int version, int size, String search, WorkspaceCandidateKey key,
                  @JsonInclude(JsonInclude.Include.NON_NULL) String tenantId) {

        public Cursor(String subjectId, int version, int size, String search, WorkspaceCandidateKey key) {
            this(subjectId, version, size, search, key, null);
        }

        public Cursor {
            PlatformChecks.id(subjectId, "subjectId");
            PlatformChecks.require(version == 3 && key != null, "invalid cursor");
            validateQuery(size, search);
            if (tenantId != null) {
                PlatformChecks.id(tenantId, "tenantId");
                PlatformChecks.require(tenantId.equals(key.scope().tenantId()), "cursor boundary outside tenant");
            }
        }
    }

    /**
     * 租户游标与空间游标使用不同的存储命名空间。
     */
    record TenantCursor(String subjectId, int version, int size, String search, TenantTypeEnum type,
                        String afterTenantId) {
        public TenantCursor {
            PlatformChecks.id(subjectId, "subjectId");
            PlatformChecks.id(afterTenantId, "afterTenantId");
            PlatformChecks.require(version == 1, "invalid tenant cursor");
            validateQuery(size, search);
        }
    }

    private static void validateQuery(int size, String search) {
        PlatformChecks.require(size >= 1 && size <= 100 && search != null
                && search.length() <= 300 && search.equals(search.strip().toLowerCase(java.util.Locale.ROOT))
                && search.chars().noneMatch(Character::isISOControl), "noncanonical cursor search");
    }

    /**
     * 保存导航状态并生成新的不透明令牌，有效期由存储实现配置。
     *
     * @param cursor 已通过结构校验的游标状态
     * @return 客户端用于继续浏览的令牌
     * @throws com.arte.core.exception.platform.PlatformException 游标存储不可用
     */
    String save(Cursor cursor);

    /**
     * 读取令牌对应的有效导航状态，不验证其与当前请求主体或查询条件是否一致。
     *
     * @param token 客户端提交的不透明游标令牌
     * @return 尚未失效且通过结构校验的游标状态
     * @throws IllegalArgumentException                           令牌格式不合法
     * @throws com.arte.core.exception.platform.PlatformException 游标不存在、过期或存储内容失效，或游标存储不可用
     */
    Cursor load(String token);

    String saveTenant(TenantCursor cursor);

    TenantCursor loadTenant(String token);
}
