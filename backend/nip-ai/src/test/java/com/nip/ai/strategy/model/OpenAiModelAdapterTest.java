package com.nip.ai.strategy.model;

import com.nip.ai.common.enums.ReasoningEffortEnum;
import com.nip.ai.pojo.chat.ChatRequestDto;
import com.nip.core.enums.TextTypeEnum;
import com.nip.core.exception.ChatException;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.HashMap;
import java.util.Map;

public class OpenAiModelAdapterTest {

    private final OpenAiModelAdapter adapter = new OpenAiModelAdapter() {
        @Override
        public String modelId() {
            return "test-model";
        }
    };

    @Test
    public void shouldBuildRequestOptions() {
        Map<String, Object> extraParam = new HashMap<>();
        extraParam.put("vendor_parameter", true);
        ChatRequestDto request = new ChatRequestDto()
                .setModelId("gpt-test")
                .setUserName("tester")
                .setTemperature(0.3)
                .setTopP(0.8)
                .setMaxTokens(512)
                .setPresencePenalty(0.1)
                .setFrequencyPenalty(0.2)
                .setReasoningEffort(ReasoningEffortEnum.MEDIUM)
                .setTextType(TextTypeEnum.JSON)
                .setExtraParam(extraParam);

        OpenAiChatOptions options = adapter.buildOptions(request);
        extraParam.put("changed_after_build", true);

        Assert.assertEquals("gpt-test", options.getModel());
        Assert.assertEquals("tester", options.getUser());
        Assert.assertEquals(Double.valueOf(0.3), options.getTemperature());
        Assert.assertEquals(Double.valueOf(0.8), options.getTopP());
        Assert.assertEquals(Integer.valueOf(512), options.getMaxCompletionTokens());
        Assert.assertEquals("medium", options.getReasoningEffort());
        Assert.assertNotNull(options.getStreamOptions());
        Assert.assertTrue(options.getStreamOptions().includeUsage());
        Assert.assertEquals(ResponseFormat.Type.JSON_OBJECT, options.getResponseFormat().getType());
        Assert.assertEquals(Boolean.TRUE, options.getExtraBody().get("vendor_parameter"));
        Assert.assertFalse(options.getExtraBody().containsKey("changed_after_build"));
    }

    @Test
    public void shouldNotForceReasoningEffort() {
        OpenAiChatOptions options = adapter.buildOptions(
                new ChatRequestDto().setModelId("gpt-test"));

        Assert.assertNull(options.getReasoningEffort());
    }

    @Test
    public void shouldParseHttpAndSocksProxy() {
        assertProxy(OpenAiModelAdapter.parseProxy("127.0.0.1:7897"),
                Proxy.Type.HTTP, "127.0.0.1", 7897);
        assertProxy(OpenAiModelAdapter.parseProxy("socks5://localhost:1080"),
                Proxy.Type.SOCKS, "localhost", 1080);
    }

    @Test(expected = ChatException.class)
    public void shouldRejectProxyWithoutPort() {
        OpenAiModelAdapter.parseProxy("http://localhost");
    }

    @Test
    public void shouldExposeGpt56ModelIds() {
        Assert.assertEquals("gpt-5.6-sol", new Gpt56SolModelAdapter().modelId());
        Assert.assertEquals("gpt-5.6-terra", new Gpt56TerraModelAdapter().modelId());
        Assert.assertEquals("gpt-5.6-luna", new Gpt56LunaModelAdapter().modelId());
    }

    private static void assertProxy(Proxy proxy, Proxy.Type type, String host, int port) {
        Assert.assertEquals(type, proxy.type());
        InetSocketAddress address = (InetSocketAddress) proxy.address();
        Assert.assertEquals(host, address.getHostString());
        Assert.assertEquals(port, address.getPort());
    }
}
