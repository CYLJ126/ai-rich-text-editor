package com.arte.app.platform.persistence.mapper;

import com.arte.app.pojo.platform.PlatformAuditSinkDto;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 平台操作审计
 * <p>
 * 记录实际操作者、操作范围及关联命令。
 * 仅由平台专用会话注册，不加入旧 Mapper 扫描；调用方负责完整范围和事务门控。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:28 ✾
 */
public interface PlatformAuditMapper extends BaseMapper<PlatformAuditSinkDto> {
}
