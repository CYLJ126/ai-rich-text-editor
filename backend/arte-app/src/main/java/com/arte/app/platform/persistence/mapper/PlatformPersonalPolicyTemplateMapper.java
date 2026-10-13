package com.arte.app.platform.persistence.mapper;

import com.arte.app.pojo.platform.PlatformPersonalPolicyTemplateDto;

/**
 * 固定个人模板的发布指针当前读；历史修订由部署迁移追加。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/13 17:22 ✾
 */
public interface PlatformPersonalPolicyTemplateMapper {
    PlatformPersonalPolicyTemplateDto selectCurrent();
}
