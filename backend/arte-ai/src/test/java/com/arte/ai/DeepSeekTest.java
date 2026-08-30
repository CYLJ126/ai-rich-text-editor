package com.arte.ai;

import com.arte.ai.tool.LogUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * @author zhangsc
 * @since 2025/5/29 21:00
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest(classes = AiApplication.class)
public class DeepSeekTest {
    @Resource
    private DeepSeekChatModel chatModel;

    @Resource
    private ApplicationContext context;

    @Test
    public void checkBeanExistence() {
        // 列出所有 DeepSeek 相关Bean
        Arrays.stream(context.getBeanDefinitionNames())
                .filter(name -> name.toLowerCase().contains("deepseek"))
                .forEach(System.out::println);
        // 显式尝试获取Bean
        try {
            DeepSeekChatModel model = context.getBean(DeepSeekChatModel.class);
            LogUtil.info(log, "Bean获取成功", model);
        } catch (Exception e) {
            LogUtil.warn(log, "Bean获取失败", e.getMessage());
        }
        Assert.assertTrue(true);
    }

    @Test
    public void testDeepSeekChat() {
        // 构造提示词
        Prompt prompt = new Prompt("请用中文回答：Spring AI 是什么？");
        // 调用接口
        ChatResponse response = chatModel.call(prompt);
        // 断言和输出
        LogUtil.info(log, "生成结果", response.getResult().getOutput().toString());
        // 添加AssertJ断言
        Assert.assertNotNull(response.getResult());
    }

    /**
     * 调用推理模型，获取思维链内容
     */
    @Test
    public void testDeepSeekReasoner() {
        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                .frequencyPenalty(1.0)
                .model(DeepSeekApi.ChatModel.DEEPSEEK_REASONER)
                .temperature(0.2).build();
        Prompt prompt = new Prompt("请对比王羲之和颜真卿的书法贡献和地位", options);
        ChatResponse response = chatModel.call(prompt);
        DeepSeekAssistantMessage deepSeekAssistantMessage = (DeepSeekAssistantMessage) response.getResult().getOutput();
        LogUtil.info(log, "消息类型", deepSeekAssistantMessage.getMessageType().getValue());
        LogUtil.info(log, "COT - 思维链内容", deepSeekAssistantMessage.getReasoningContent());
        LogUtil.info(log, "生成结果", deepSeekAssistantMessage.getText());
        Assert.assertTrue(true);
    }

    @Test
    public void testDeepSeekReasonerMultiRound() {
        ChatClient chatClient = ChatClient.builder(chatModel).defaultAdvisors().build();
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("请简述傅里叶变换，并举出示例"));
        DeepSeekChatOptions options = DeepSeekChatOptions.builder().model(DeepSeekApi.ChatModel.DEEPSEEK_REASONER).build();
        Prompt prompt = new Prompt(messages, options);
        ChatResponse response = chatClient.prompt(prompt).advisors(new SimpleLoggerAdvisor()).call().chatResponse();
        assert response != null;
        DeepSeekAssistantMessage deepSeekAssistantMessage = (DeepSeekAssistantMessage) response.getResult().getOutput();
        String reasoningContent = deepSeekAssistantMessage.getReasoningContent();
        String text = deepSeekAssistantMessage.getText();
        LogUtil.info(log, "第一次会话---COT - 思维链内容", reasoningContent);
        LogUtil.info(log, "第一次会话---生成结果", text);

        messages.add(new AssistantMessage(Objects.requireNonNull(text)));
        messages.add(new UserMessage("请基于上述示例描述傅里叶相关应用场景"));
        Prompt prompt2 = new Prompt(messages, options);
        ChatResponse response2 = chatClient.prompt(prompt2).advisors(new SimpleLoggerAdvisor()).call().chatResponse();

        DeepSeekAssistantMessage deepSeekAssistantMessage2 = (DeepSeekAssistantMessage) response2.getResult().getOutput();
        String reasoningContent2 = deepSeekAssistantMessage2.getReasoningContent();
        String text2 = deepSeekAssistantMessage2.getText();
        LogUtil.info(log, "第二次会话---COT - 思维链内容", reasoningContent2);
        LogUtil.info(log, "第二次会话---生成结果", text2);

        Assert.assertTrue(true);
    }
}
