package com.arte.ai.advisor;

import cn.hutool.core.util.StrUtil;
import com.arte.ai.common.enums.MessageRoleEnum;
import com.arte.ai.common.enums.MessageStatusEnum;
import com.arte.ai.mcp.server.McpTranslationPrompt;
import com.arte.ai.pojo.chat.ChatRequestDto;
import com.arte.ai.pojo.message.MessageDto;
import com.arte.core.enums.TextTypeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Objects;

import static com.arte.ai.common.constant.PromptConstant.CURSOR_MARK;

/**
 * 生成类型处理
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/7/28 19:46 ✾
 **/
@NullMarked
@Slf4j
@Service
public class GenerateTypeHandlingAdvisor extends PersistentChatMemoryAdvisor {

    @Resource
    private McpTranslationPrompt mcpTranslationPrompt;

    @Override
    public int getOrder() {
        // 请求时倒数第二个被调用
        return 998;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        ChatRequestDto chatRequestDto = getChatRequestDto(chatClientRequest);
        if (chatRequestDto.getGenerateType() == null) {
            return chatClientRequest;
        }
        String userPrompt = chatRequestDto.getContent();
        switch (chatRequestDto.getGenerateType()) {
            case SUMMARY:
                userPrompt = StrUtil.blankToDefault(userPrompt, "请根据提示进行内容总结。");
                break;
            case POLISH:
                userPrompt = StrUtil.blankToDefault(userPrompt, "请根据提示进行润色。");
                break;
            case CONTINUATION:
                userPrompt = StrUtil.blankToDefault(userPrompt, String.format("请根据提示在 %s 处进行补全。", CURSOR_MARK));
                break;
            case TRANSLATE:
                userPrompt = mcpTranslationPrompt.generatePrompt(
                        chatRequestDto.getOriginalText(),
                        chatRequestDto.getOriginalLanguage(),
                        chatRequestDto.getTargetLanguage(),
                        chatRequestDto.getContent()
                );
                break;
            default:
                break;
        }
        chatClientRequest.prompt().getInstructions().addLast(new UserMessage(userPrompt));
        MessageDto messageDto = new MessageDto();
        // 生成类消息的用户消息，用会话 ID 来标记使用场景
        String convId = Objects.nonNull(chatRequestDto.getScene()) ? chatRequestDto.getScene().getValue() : StrUtil.EMPTY;
        messageDto.setConvId(convId);
        messageDto.setMessageId(requireMessageId(chatRequestDto.getUserMessageId(), "用户消息 ID 不能为空"));
        messageDto.setRole(MessageRoleEnum.USER);
        messageDto.setContent(userPrompt);
        messageDto.setTextType(TextTypeEnum.PLAIN);
        messageDto.setStatus(MessageStatusEnum.COMPLETED);
        messageDto.setCreateBy(chatRequestDto.getUserName());
        messageDto.setUpdateBy(chatRequestDto.getUserName());
        LocalDateTime now = LocalDateTime.now();
        messageDto.setCreateTime(now);
        messageDto.setUpdateTime(now);
        messageService.save(messageDto);
        putIntoRequest(false, chatClientRequest,
                USER_MESSAGE_ID_KEY, messageDto.getMessageId(),
                STREAMING_START_TIME_KEY, System.currentTimeMillis());
        return chatClientRequest;
    }

    @Override
    protected void customizeResponseMessage(MessageDto message, ChatRequestDto chatRequest) {
        message.setConvId(chatRequest.getScene().getValue());
        message.setTextType(TextTypeEnum.MARKDOWN);
    }

    @Override
    protected void afterResponsePersisted(ChatClientResponse response, ResponsePersistenceResult result) {
        log.info("生成类型：{}，用户消息 ID：{}，耗时：{} ms，响应元数据：{}",
                result.chatRequest().getGenerateType(), getFromResponse(response, USER_MESSAGE_ID_KEY),
                result.latency(), result.metadata());
    }
}
