package com.nip.ai.pojo.conversation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.nip.ai.pojo.BaseDto;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 会话摘要实体
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 12:44 ✾
 **/
@Getter
@Setter
@ToString
@Accessors(chain = true)
@TableName("nip_ai_conversation_summary")
public class ConversationSummaryPo extends BaseDto implements Serializable {
    @Serial
    private static final long serialVersionUID = 1052642139773399445L;
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    @TableField("conv_id")
    private String convId;
    @TableField("summary_content")
    private String summaryContent;
    @TableField("start_message_id")
    private String startMessageId;
    @TableField("end_message_id")
    private String endMessageId;
    @TableField("covered_message_count")
    private Integer coveredMessageCount;
    @TableField("tokens_before")
    private Integer tokensBefore;
    @TableField("tokens_after")
    private Integer tokensAfter;
    @TableField("row_version")
    private Integer rowVersion;

}