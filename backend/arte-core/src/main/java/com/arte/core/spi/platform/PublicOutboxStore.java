package com.arte.core.spi.platform;

import com.arte.core.pojo.platform.PublicEvent;

/**
 * 公共事件的可靠存储接口
 * <p>
 * 与状态变更同事务保存可靠事件，提交后由适配器投递。重复 ID 同内容幂等，不同内容必须报冲突。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public interface PublicOutboxStore {

    /**
     * 与状态变更同事务保存可靠事件，相同事件 ID 的内容冲突时报告失败。
     */
    void append(PublicEvent event);
}
