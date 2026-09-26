package com.arte.ai.service;

import cn.hutool.core.util.StrUtil;
import com.arte.ai.api.ModelConfigService;
import com.arte.ai.common.enums.ModelProviderEnum;
import com.arte.ai.pojo.model.AvailableModelDto;
import com.arte.ai.pojo.model.AvailableModelQuery;
import com.arte.ai.pojo.model.ModelConfigDto;
import com.arte.core.exception.BusinessException;
import com.arte.core.pojo.UserContext;
import com.arte.core.utils.crypto.Sm2UtilForSmCrypto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 调用各模型提供商的模型列表接口，并统一为前端下拉选项。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelCatalogService {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final ModelConfigService modelConfigService;
    private final ObjectMapper objectMapper;

    @Value("${security.sm2.privateKey}")
    private String privateKeyText;

    public List<AvailableModelDto> listAvailableModels(AvailableModelQuery query) {
        if (query == null || query.getProvider() == null) {
            throw new BusinessException("error.ai.providerRequired");
        }

        ProviderCredentials credentials = resolveCredentials(query);
        try {
            List<JsonNode> nodes = fetchModelNodes(query.getProvider(), credentials);
            return nodes.stream()
                    .filter(node -> isChatModel(query.getProvider(), node))
                    .map(node -> toAvailableModel(query.getProvider(), node))
                    .filter(model -> StrUtil.isNotBlank(model.modelId()))
                    .sorted(Comparator.comparing(AvailableModelDto::modelId))
                    .toList();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("获取模型列表失败, provider={}, baseUrl={}, error={}",
                    query.getProvider(), credentials.baseUrl(), e.getMessage());
            throw new BusinessException("error.ai.availableModelsFetchFailed", e);
        }
    }

    private ProviderCredentials resolveCredentials(AvailableModelQuery query) {
        ModelConfigDto stored = null;
        if (query.getModelConfigId() != null) {
            stored = modelConfigService.getOwnedModel(query.getModelConfigId(), UserContext.getUserName());
        }

        String encryptedApiKey = query.getApiKey();
        String apiKeySource = "request";
        if (StrUtil.isBlank(encryptedApiKey) || ModelConfigService.MASKED_API_KEY.equals(encryptedApiKey)) {
            if (stored != null && stored.getProvider() == query.getProvider()) {
                encryptedApiKey = stored.getApiKey();
                apiKeySource = "stored-model-config";
            }
        }
        if (StrUtil.isBlank(encryptedApiKey)) {
            throw new BusinessException("error.ai.apiKeyRequiredForModels");
        }

        String apiKey;
        try {
            apiKey = Sm2UtilForSmCrypto.decryptForSmCrypto(encryptedApiKey, privateKeyText);
        } catch (RuntimeException e) {
            log.warn("API Key 解密失败, provider={}, modelConfigId={}, keySource={}, cipherLength={}, cause={}",
                    query.getProvider(), query.getModelConfigId(), apiKeySource,
                    encryptedApiKey.length(), e.getMessage(), e);
            throw new BusinessException("error.ai.apiKeyDecryptFailed", e);
        }

        String baseUrl = StrUtil.blankToDefault(query.getApiBaseUrl(),
                stored != null && stored.getProvider() == query.getProvider() ? stored.getApiBaseUrl() : null);
        baseUrl = StrUtil.blankToDefault(baseUrl, query.getProvider().getDefaultApiBaseUrl());
        return new ProviderCredentials(apiKey, stripTrailingSlash(baseUrl));
    }

    private List<JsonNode> fetchModelNodes(ModelProviderEnum provider, ProviderCredentials credentials) throws Exception {
        if (provider == ModelProviderEnum.QIAN_WEN) {
            return fetchQianWenModels(credentials);
        }

        String modelsUrl = resolveModelsUrl(provider, credentials.baseUrl());
        if (provider == ModelProviderEnum.CLAUDE) {
            modelsUrl += "?limit=1000";
        }
        JsonNode root = fetchJson(provider, credentials.apiKey(), modelsUrl);
        return extractArray(root, "data");
    }

    private List<JsonNode> fetchQianWenModels(ProviderCredentials credentials) throws Exception {
        String modelsUrl = resolveModelsUrl(ModelProviderEnum.QIAN_WEN, credentials.baseUrl());
        List<JsonNode> result = new ArrayList<>();
        int page = 1;
        int total = Integer.MAX_VALUE;
        while (result.size() < total && page <= 20) {
            String url = modelsUrl + "?page_no=" + page + "&page_size=100&capabilities=TG";
            JsonNode root = fetchJson(ModelProviderEnum.QIAN_WEN, credentials.apiKey(), url);
            JsonNode output = root.path("output");
            JsonNode models = output.path("models");
            if (!models.isArray()) {
                break;
            }
            models.forEach(result::add);
            total = output.path("total").asInt(result.size());
            if (models.size() == 0) {
                break;
            }
            page++;
        }
        return result;
    }

    private JsonNode fetchJson(ModelProviderEnum provider, String apiKey, String url) throws Exception {
        WebClient.RequestHeadersSpec<?> request = WebClient.builder()
                .build()
                .get()
                .uri(url)
                .header(HttpHeaders.ACCEPT, "application/json");
        if (provider == ModelProviderEnum.CLAUDE) {
            request = request.header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01");
        } else {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        String body = request.retrieve()
                .bodyToMono(String.class)
                .timeout(REQUEST_TIMEOUT)
                .block();
        return objectMapper.readTree(StrUtil.blankToDefault(body, "{}"));
    }

    static String resolveModelsUrl(ModelProviderEnum provider, String baseUrl) {
        String normalized = stripTrailingSlash(StrUtil.blankToDefault(baseUrl, provider.getDefaultApiBaseUrl()));
        if (provider == ModelProviderEnum.QIAN_WEN) {
            normalized = normalized.replace("/compatible-mode/v1", "/api/v1");
        }
        return normalized.endsWith("/models") ? normalized : normalized + "/models";
    }

    private static String stripTrailingSlash(String value) {
        String result = StrUtil.trim(value);
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static List<JsonNode> extractArray(JsonNode root, String fieldName) {
        JsonNode array = root.isArray() ? root : root.path(fieldName);
        if (!array.isArray()) {
            return List.of();
        }
        List<JsonNode> result = new ArrayList<>();
        array.forEach(result::add);
        return result;
    }

    static AvailableModelDto toAvailableModel(ModelProviderEnum provider, JsonNode node) {
        String modelId = firstText(node, "id", "model");
        String modelName = firstText(node, "display_name", "name", "id", "model");
        ModelCapabilities maintained = maintainedCapabilities(provider, modelId);

        boolean supportVision = maintained.vision();
        boolean supportFunction = maintained.function();
        boolean supportThinking = maintained.thinking();
        boolean supportSearch = maintained.search();
        Integer contextWindow = null;
        Integer maxTokens = null;

        if (provider == ModelProviderEnum.QIAN_WEN) {
            supportVision |= arrayContains(node.path("capabilities"), "VU")
                    || arrayContains(node.path("inference_metadata").path("request_modality"), "Image");
            supportFunction |= arrayContains(node.path("features"), "function-calling");
            supportThinking |= arrayContains(node.path("capabilities"), "Reasoning");
            supportSearch |= arrayContains(node.path("features"), "web-search");
            contextWindow = nullableInt(node.path("model_info").path("context_window"));
            maxTokens = nullableInt(node.path("model_info").path("max_output_tokens"));
        } else if (provider == ModelProviderEnum.CLAUDE) {
            supportVision |= capabilitySupported(node, "image_input");
            supportThinking |= capabilitySupported(node, "thinking");
            contextWindow = nullableInt(node.path("max_input_tokens"));
            maxTokens = nullableInt(node.path("max_tokens"));
        } else if (provider == ModelProviderEnum.MISTRAL) {
            supportVision |= node.path("capabilities").path("vision").asBoolean(false);
            supportFunction |= node.path("capabilities").path("function_calling").asBoolean(false);
            contextWindow = nullableInt(node.path("max_context_length"));
        } else if (provider == ModelProviderEnum.OPEN_ROUTER) {
            supportVision |= arrayContains(node.path("architecture").path("input_modalities"), "image");
            supportFunction |= arrayContains(node.path("supported_parameters"), "tools")
                    || arrayContains(node.path("supported_parameters"), "tool_choice");
            supportThinking |= arrayContains(node.path("supported_parameters"), "reasoning");
            supportSearch |= arrayContains(node.path("supported_parameters"), "web_search");
            contextWindow = nullableInt(node.path("context_length"));
            maxTokens = nullableInt(node.path("top_provider").path("max_completion_tokens"));
        }

        return new AvailableModelDto(modelId, StrUtil.blankToDefault(modelName, modelId),
                supportVision, supportFunction, supportThinking, supportSearch,
                contextWindow, maxTokens);
    }

    private static boolean isChatModel(ModelProviderEnum provider, JsonNode node) {
        if (provider == ModelProviderEnum.MISTRAL && node.path("capabilities").has("completion_chat")) {
            return node.path("capabilities").path("completion_chat").asBoolean(false);
        }
        String id = firstText(node, "id", "model").toLowerCase(Locale.ROOT);
        return !(id.contains("embedding")
                || id.contains("moderation")
                || id.contains("transcri")
                || id.contains("whisper")
                || id.contains("tts")
                || id.contains("rerank")
                || id.contains("realtime")
                || id.startsWith("dall-e")
                || id.startsWith("gpt-image"));
    }

    private static ModelCapabilities maintainedCapabilities(ModelProviderEnum provider, String modelId) {
        String id = StrUtil.nullToEmpty(modelId).toLowerCase(Locale.ROOT);
        return switch (provider) {
            case OPENAI -> new ModelCapabilities(
                    startsWithAny(id, "gpt-4", "gpt-5", "o1", "o3", "o4"),
                    startsWithAny(id, "gpt-4", "gpt-5", "o1", "o3", "o4"),
                    startsWithAny(id, "gpt-5", "o1", "o3", "o4"),
                    id.contains("search") || id.startsWith("gpt-5.6"));
            case DEEPSEEK -> new ModelCapabilities(
                    id.contains("vision") || id.contains("flash"),
                    !id.contains("reasoner"),
                    id.contains("reasoner") || id.contains("r1") || id.contains("v4"),
                    false);
            case QIAN_WEN -> new ModelCapabilities(
                    id.contains("vl") || id.contains("omni"),
                    id.startsWith("qwen"),
                    id.contains("qwq") || id.contains("reason") || id.matches("qwen3([.-].*)?"),
                    false);
            case CLAUDE -> new ModelCapabilities(
                    id.startsWith("claude-"),
                    id.startsWith("claude-"),
                    id.contains("sonnet") || id.contains("opus"),
                    false);
            case MISTRAL -> new ModelCapabilities(
                    id.contains("pixtral") || id.contains("vision"),
                    id.contains("mistral") || id.contains("ministral") || id.contains("mixtral"),
                    id.contains("reason") || id.contains("magistral"),
                    false);
            case OPEN_ROUTER -> new ModelCapabilities(false, false, false, false);
        };
    }

    private static boolean startsWithAny(String value, String... prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean capabilitySupported(JsonNode node, String capability) {
        return node.path("capabilities").path(capability).path("supported").asBoolean(false);
    }

    private static boolean arrayContains(JsonNode array, String expected) {
        if (!array.isArray()) {
            return false;
        }
        for (JsonNode value : array) {
            if (expected.equalsIgnoreCase(value.asText())) {
                return true;
            }
        }
        return false;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText();
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private static Integer nullableInt(JsonNode node) {
        return node.isNumber() && node.asInt() > 0 ? node.asInt() : null;
    }

    private record ProviderCredentials(String apiKey, String baseUrl) {
    }

    private record ModelCapabilities(boolean vision, boolean function, boolean thinking, boolean search) {
    }
}
