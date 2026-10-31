package com.arte.ainew.web.response;

import com.arte.ainew.pojo.control.ChatConfigurationOptions;

import java.util.List;

/**
 * 响应前端的聊天配置选项
 * <p>
 * 显式公开的选择 DTO，不序列化内部固定目录、连接或 Grant。
 *
 * @param modelOptions 可执行模型的公开选项列表
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/8 05:52 ✾
 */
public record ChatConfigurationResponse(List<ChatConfigurationOptions.ModelOption> modelOptions) {

    public ChatConfigurationResponse {
        modelOptions = List.copyOf(modelOptions);
    }

    public static ChatConfigurationResponse from(ChatConfigurationOptions configuration) {
        return new ChatConfigurationResponse(configuration.options());
    }
}
