package com.arte.ai.api;

import com.baomidou.mybatisplus.extension.service.IService;
import com.arte.ai.pojo.assistant.AssistantDto;
import com.arte.ai.pojo.assistant.AssistantParam;
import com.arte.core.pojo.PageView;

/**
 * AI 助手表 服务类
 *
 * @author zhangsc
 * @since 2026-07-13
 */
public interface AssistantService extends IService<AssistantDto> {

    PageView<AssistantDto> listAssistants(AssistantParam param);

    AssistantDto getDefaultAssistant(String userName);
}
