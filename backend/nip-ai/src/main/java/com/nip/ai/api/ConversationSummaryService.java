package com.nip.ai.api;

import com.baomidou.mybatisplus.extension.service.IService;
import com.nip.ai.pojo.conversation.ConversationSummaryDto;

/**
 * 会话摘要 Service
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 16:35 ✾
 **/
public interface ConversationSummaryService extends IService<ConversationSummaryDto> {

    String getLatestSummaryContent(String convId);

    void checkAndSummaryAsync(String convId);


}
