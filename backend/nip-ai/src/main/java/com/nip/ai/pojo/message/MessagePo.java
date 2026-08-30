package com.nip.ai.pojo.message;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.nip.ai.common.enums.MessageRoleEnum;
import com.nip.ai.common.enums.MessageStatusEnum;
import com.nip.ai.pojo.BaseDto;
import com.nip.core.enums.CurrencyEnum;
import com.nip.core.enums.TextTypeEnum;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;
import org.apache.ibatis.type.JdbcType;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * AI 消息实体
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 12:42 ✾
 */
@Getter
@Setter
@ToString
@Accessors(chain = true)
@TableName(value = "nip_ai_message", autoResultMap = true)
public class MessagePo extends BaseDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 2630158049364132557L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * 消息业务ID(UUID)
     */
    private String messageId;

    /**
     * 会话业务ID
     */
    private String convId;

    /**
     * 父消息ID(分支)
     */
    private String parentMessageId;

    /**
     * 分支ID
     */
    private String branchId;

    /**
     * 同级分支序号
     */
    private Integer branchIndex;

    /**
     * 角色: user/assistant/system/tool
     */
    private MessageRoleEnum role;

    /**
     * 消息文本内容
     */
    @TableField(jdbcType = JdbcType.LONGVARCHAR)
    private String content;

    /**
     * 优化后的内容，如开启了查询优化功能时
     */
    @TableField(jdbcType = JdbcType.LONGVARCHAR)
    private String optimizedContent;
    /**
     * 文本类型
     */
    private TextTypeEnum textType;

    /**
     * 生成此消息的模型
     */
    private Integer modelId;

    /**
     * 调用实际参数（JSON 存储）
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> modelParam;

    /**
     * 思考内容(reasoning_content)
     */
    @TableField(jdbcType = JdbcType.LONGVARCHAR)
    private String reasoningContent;

    /**
     * 工具调用信息（JSON 存储）
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> toolCalls;

    /**
     * 引用消息ID
     */
    private String quotedMessageId;

    /**
     * 引用消息快照(防历史变更)
     */
    @TableField(jdbcType = JdbcType.LONGVARCHAR)
    private String quotedSnapshot;

    /**
     * 状态: pending/streaming/completed/failed/stopped
     */
    private MessageStatusEnum status;

    /**
     * 点赞状态: 1点赞/-1点踩/0无
     */
    private Integer likeStatus;

    /**
     * 是否逻辑删除
     */
    private Boolean deleteFlag;

    /**
     * 结束原因: stop/length/tool_calls/content_filter
     */
    private String finishReason;

    /**
     * Prompt Token 数
     */
    private Integer promptToken;

    /**
     * Completion Token 数
     */
    private Integer completionToken;

    /**
     * Total Token 数
     */
    private Integer totalToken;

    /**
     * 推理 Token 数
     */
    private Integer reasoningToken;

    /**
     * 响应延迟(ms)
     */
    private Integer latencyMs;

    /**
     * 首Token延迟(ms)
     */
    private Integer firstTokenMs;

    /**
     * 输入成本
     */
    private BigDecimal promptCost;

    /**
     * 输出成本
     */
    private BigDecimal completionCost;

    /**
     * 币种
     */
    private CurrencyEnum currency;

    /**
     * 错误码
     */
    private String errorCode;

    /**
     * 错误信息
     */
    @TableField(jdbcType = JdbcType.LONGVARCHAR)
    private String errorMessage;

    /**
     * 重试次数
     */
    private Integer retryCount;

    /**
     * 模型侧请求ID
     */
    private String requestId;

    public static final String COL_ID = "id";
    public static final String COL_MESSAGE_ID = "message_id";
    public static final String COL_CONV_ID = "conv_id";
    public static final String COL_PARENT_MESSAGE_ID = "parent_message_id";
    public static final String COL_BRANCH_ID = "branch_id";
    public static final String COL_BRANCH_INDEX = "branch_index";
    public static final String COL_ROLE = "role";
    public static final String COL_CONTENT = "content";
    public static final String COL_OPTIMIZED_CONTENT = "optimized_content";
    public static final String COL_CONTENT_TYPE = "content_type";
    public static final String COL_MODEL_ID = "model_id";
    public static final String COL_MODEL_PROVIDER = "model_provider";
    public static final String COL_MODEL_PARAM = "model_param";
    public static final String COL_THINK_CONTENT = "think_content";
    public static final String COL_TOOL_CALLS = "tool_calls";
    public static final String COL_QUOTED_MESSAGE_ID = "quoted_message_id";
    public static final String COL_QUOTED_SNAPSHOT = "quoted_snapshot";
    public static final String COL_STATUS = "status";
    public static final String COL_LIKE_STATUS = "like_status";
    public static final String COL_DELETE_FLAG = "delete_flag";
    public static final String COL_FINISH_REASON = "finish_reason";
    public static final String COL_PROMPT_TOKEN = "prompt_token";
    public static final String COL_COMPLETION_TOKEN = "completion_token";
    public static final String COL_TOTAL_TOKEN = "total_token";
    public static final String COL_REASONING_TOKEN = "reasoning_token";
    public static final String COL_LATENCY_MS = "latency_ms";
    public static final String COL_FIRST_TOKEN_MS = "first_token_ms";
    public static final String COL_INPUT_COST = "input_cost";
    public static final String COL_OUTPUT_COST = "output_cost";
    public static final String COL_CURRENCY = "currency";
    public static final String COL_ERROR_CODE = "error_code";
    public static final String COL_ERROR_MESSAGE = "error_message";
    public static final String COL_RETRY_COUNT = "retry_count";
    public static final String COL_REQUEST_ID = "request_id";
    public static final String COL_CREATE_BY = "create_by";
    public static final String COL_UPDATE_BY = "update_by";
    public static final String COL_CREATE_TIME = "create_time";
    public static final String COL_UPDATE_TIME = "update_time";

}