package com.arte.ai.service;

import com.arte.ai.common.enums.ModelProviderEnum;
import com.arte.ai.pojo.model.AvailableModelDto;
import org.junit.Assert;
import org.junit.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class ModelCatalogServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    public void shouldResolveProviderModelsUrls() {
        Assert.assertEquals(
                "https://api.openai.com/v1/models",
                ModelCatalogService.resolveModelsUrl(ModelProviderEnum.OPENAI, null));
        Assert.assertEquals(
                "https://dashscope.aliyuncs.com/api/v1/models",
                ModelCatalogService.resolveModelsUrl(
                        ModelProviderEnum.QIAN_WEN,
                        "https://dashscope.aliyuncs.com/compatible-mode/v1/"));
    }

    @Test
    public void shouldReadQianWenCapabilities() throws Exception {
        JsonNode node = objectMapper.readTree("""
                {
                  "model": "qwen3-max",
                  "name": "通义千问3-Max",
                  "capabilities": ["TG", "Reasoning", "VU"],
                  "features": ["function-calling", "web-search"],
                  "model_info": {"context_window": 131072, "max_output_tokens": 16384}
                }
                """);

        AvailableModelDto model = ModelCatalogService.toAvailableModel(ModelProviderEnum.QIAN_WEN, node);

        Assert.assertEquals("qwen3-max", model.modelId());
        Assert.assertEquals("通义千问3-Max", model.modelName());
        Assert.assertTrue(model.supportVision());
        Assert.assertTrue(model.supportFunction());
        Assert.assertTrue(model.supportThinking());
        Assert.assertTrue(model.supportSearch());
        Assert.assertEquals(Integer.valueOf(131072), model.contextWindow());
        Assert.assertEquals(Integer.valueOf(16384), model.maxTokens());
    }

    @Test
    public void shouldReadProviderSpecificCapabilities() throws Exception {
        JsonNode claude = objectMapper.readTree("""
                {
                  "id": "claude-sonnet-5",
                  "display_name": "Claude Sonnet 5",
                  "capabilities": {
                    "image_input": {"supported": true},
                    "thinking": {"supported": true}
                  },
                  "max_input_tokens": 200000,
                  "max_tokens": 64000
                }
                """);
        JsonNode openRouter = objectMapper.readTree("""
                {
                  "id": "vendor/model",
                  "name": "Vendor Model",
                  "architecture": {"input_modalities": ["text", "image"]},
                  "supported_parameters": ["tools", "reasoning", "web_search"],
                  "context_length": 100000,
                  "top_provider": {"max_completion_tokens": 10000}
                }
                """);

        AvailableModelDto claudeModel = ModelCatalogService.toAvailableModel(ModelProviderEnum.CLAUDE, claude);
        AvailableModelDto routerModel = ModelCatalogService.toAvailableModel(ModelProviderEnum.OPEN_ROUTER, openRouter);

        Assert.assertTrue(claudeModel.supportVision());
        Assert.assertTrue(claudeModel.supportFunction());
        Assert.assertTrue(claudeModel.supportThinking());
        Assert.assertTrue(routerModel.supportVision());
        Assert.assertTrue(routerModel.supportFunction());
        Assert.assertTrue(routerModel.supportThinking());
        Assert.assertTrue(routerModel.supportSearch());
    }
}
