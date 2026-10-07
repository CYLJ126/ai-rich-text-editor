package com.arte.ainew.web.response;

import com.arte.ainew.pojo.control.ChatConfigurationOptions;

import java.util.List;

/**
 * 显式公开的选择 DTO，不序列化内部固定目录、连接或 Grant。
 */
public record ChatConfigurationResponse(List<ChatConfigurationOptions.ModelOption> options) {
    public ChatConfigurationResponse {
        options = List.copyOf(options);
    }

    public static ChatConfigurationResponse from(ChatConfigurationOptions configuration) {
        return new ChatConfigurationResponse(configuration.options());
    }
}
