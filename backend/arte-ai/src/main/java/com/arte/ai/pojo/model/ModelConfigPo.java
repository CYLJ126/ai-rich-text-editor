package com.arte.ai.pojo.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.arte.ai.common.enums.ModelProviderEnum;
import com.arte.ai.common.enums.ModelTypeEnum;
import com.arte.ai.pojo.BaseDto;
import com.arte.core.enums.CurrencyEnum;
import com.arte.core.enums.StatusEnum;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Map;

/**
 * AI 模型配置实体
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 12:46 ✾
 **/
@Getter
@Setter
@ToString
@Accessors(chain = true)
@TableName(value = "arte_ai_model_config", autoResultMap = true)
public class ModelConfigPo extends BaseDto implements Serializable {
    @Serial
    private static final long serialVersionUID = 2541766971377702779L;

    @TableId(type = IdType.AUTO)
    private Integer id;
    /**
     * 模型提供商
     */
    private ModelProviderEnum provider;
    /**
     * 模型 ID
     */
    private String modelId;
    /**
     * 模型显示名称
     */
    private String modelName;
    /**
     * 模型类型
     */
    private ModelTypeEnum modelType;
    /**
     * API Key（加密存储）
     */
    private String apiKey;
    /**
     * 自定义 API 地址
     */
    private String apiBaseUrl;
    /**
     * API 版本号
     */
    private String apiVersion;
    /**
     * 组织 ID
     */
    private Integer orgId;
    /**
     * 默认模型参数
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> defaultParam;
    /**
     * 上下文窗口大小（token 数）
     */
    private Integer contextWindow;
    /**
     * 最大输出 token 数
     */
    private Integer maxTokens;
    /**
     * 是否支持视觉
     */
    private Boolean supportVision;
    /**
     * 是否支持函数调用
     */
    private Boolean supportFunction;
    /**
     * 是否支持深度思考
     */
    private Boolean supportThinking;
    /**
     * 是否支持联网搜索
     */
    private Boolean supportSearch;
    /**
     * 是否支持提示缓存
     */
    private Boolean supportPromptCaching;
    /**
     * 输入单位价格
     */
    private BigDecimal inputUnitPrice;
    /**
     * 输出单位价格
     */
    private BigDecimal outputUnitPrice;
    /**
     * 货币单位
     */
    private CurrencyEnum priceCurrency;
    /**
     * 请求超时时间(秒)
     */
    private Integer timeoutSeconds;
    /**
     * 最大重试次数
     */
    private Integer maxRetries;
    /**
     * 图标
     */
    private String icon;
    /**
     * 状态: 1-启用；3-禁用
     */
    private StatusEnum status;
    /**
     * 置顶状态
     */
    private Boolean pinFlag;
    /**
     * 代理，如 127.0.0.1:7897，不配则不使用代理，用户配 127.0.0.1 表示走服务器代理
     */
    private String proxy;
    /**
     * 每分钟最大请求数 (RPM)
     */
    private Integer requestsPerMinute;
    /**
     * 每分钟最大 Token 数 (TPM)
     */
    private Integer tokensPerMinute;
    /**
     * 每日最大请求数
     */
    private Integer dailyRequestLimit;
    /**
     * 并发请求数限制
     */
    private Integer concurrencyLimit;
    /**
     * 排序
     */
    private Integer sortOrder;
    /**
     * 是否默认模型
     */
    private Boolean defaultFlag;
    /**
     * 描述
     */
    private String description;

    public static final String COL_ID = "id";
    public static final String COL_PROVIDER = "provider";
    public static final String COL_MODEL_ID = "model_id";
    public static final String COL_MODEL_NAME = "model_name";
    public static final String COL_MODEL_TYPE = "model_type";
    public static final String COL_API_KEY = "api_key";
    public static final String COL_API_BASE_URL = "api_base_url";
    public static final String COL_API_VERSION = "api_version";
    public static final String COL_ORG_ID = "org_id";
    public static final String COL_DEFAULT_PARAM = "default_param";
    public static final String COL_CONTEXT_WINDOW = "context_window";
    public static final String COL_MAX_TOKENS = "max_tokens";
    public static final String COL_SUPPORT_VISION = "support_vision";
    public static final String COL_SUPPORT_FUNCTION = "support_function";
    public static final String COL_SUPPORT_THINKING = "support_thinking";
    public static final String COL_SUPPORT_SEARCH = "support_search";
    public static final String COL_SUPPORT_PROMPT_CACHING = "support_prompt_caching";
    public static final String COL_INPUT_UNIT_PRICE = "input_unit_price";
    public static final String COL_OUTPUT_UNIT_PRICE = "output_unit_price";
    public static final String COL_PRICE_CURRENCY = "price_currency";
    public static final String COL_TIMEOUT_SECONDS = "timeout_seconds";
    public static final String COL_MAX_RETRIES = "max_retries";
    public static final String COL_ICON = "icon";
    public static final String COL_STATUS = "status";
    public static final String COL_PIN_FLAG = "pin_flag";
    public static final String COL_PROXY = "proxy";
    public static final String COL_REQUESTS_PER_MINUTE = "requests_per_minute";
    public static final String COL_TOKENS_PER_MINUTE = "tokens_per_minute";
    public static final String COL_DAILY_REQUEST_LIMIT = "daily_request_limit";
    public static final String COL_CONCURRENCY_LIMIT = "concurrency_limit";
    public static final String COL_SORT_ORDER = "sort_order";
    public static final String COL_DEFAULT_FLAG = "default_flag";
    public static final String COL_DESCRIPTION = "description";
}
