package com.nip.app.api.base;

import com.baomidou.mybatisplus.extension.service.IService;
import com.nip.app.common.enums.SummaryOperationTypeEnum;
import com.nip.app.pojo.base.SummaryDto;

/**
 * <p>
 * 总结内容表 服务类
 * </p>
 *
 * @author zhangsc
 * @since 2025-02-08
 */
public interface SummaryService extends IService<SummaryDto> {

    /**
     * 根据目标 ID 和时间维度获取总结内容
     * @param id 目标 ID
     * @param type 时间维度
     * @return 总结内容
     */
    SummaryDto getSummaryByTargetIdAndType(Integer id, String type);

    /**
     * 格式化总结内容
     *
     * @param text 总结内容
     * @return 格式化后的总结内容
     */
    String formatContent(String text, SummaryOperationTypeEnum operationType);
}
