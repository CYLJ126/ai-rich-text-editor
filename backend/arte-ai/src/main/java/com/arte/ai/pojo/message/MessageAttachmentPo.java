package com.arte.ai.pojo.message;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.arte.ai.pojo.BaseDto;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 消息附件实体
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 12:43 ✾
 **/
@Getter
@Setter
@ToString
@Accessors(chain = true)
@TableName("arte_ai_message_attachment")
public class MessageAttachmentPo extends BaseDto implements Serializable {
    @Serial
    private static final long serialVersionUID = -1159069758377864280L;
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    @TableField("message_id")
    private String messageId;
    @TableField("conv_id")
    private String convId;
    @TableField("file_name")
    private String fileName;
    @TableField("file_size")
    private Long fileSize;
    @TableField("file_type")
    private String fileType;
    @TableField("attach_type")
    private String attachType;
    @TableField("storage_path")
    private String storagePath;
    @TableField("access_url")
    private String accessUrl;
    @TableField("thumbnail_url")
    private String thumbnailUrl;
    private String status;
}
