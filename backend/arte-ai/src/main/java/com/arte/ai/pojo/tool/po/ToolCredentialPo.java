package com.arte.ai.pojo.tool.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * AI 工具凭据元数据实体
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true, exclude = "encryptedPayload")
@Accessors(chain = true)
@TableName("arte_ai_tool_credential")
public class ToolCredentialPo extends ToolPersistencePo {

    @Serial
    private static final long serialVersionUID = -8183926018610166698L;
    private String credentialId;
    private String ownerId;
    private String name;
    private String credentialType;
    private String secretReference;
    private byte[] encryptedPayload;
    private String encryptionKeyRef;
    private String maskedIdentifier;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime lastRotatedAt;
    @Version
    private Long rowVersion;
}
