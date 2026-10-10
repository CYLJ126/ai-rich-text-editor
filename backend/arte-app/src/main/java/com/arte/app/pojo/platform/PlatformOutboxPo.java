package com.arte.app.pojo.platform;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 平台事务事件发件箱：与业务事务同库提交，租约领取并发布到 Redis。
 * 数据库实体，不继承旧 BaseDto；不承担授权或对外接口职责。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:28 ✾
 */
@Data
@TableName(value = "arte_platform_outbox", autoResultMap = true)
public class PlatformOutboxPo implements java.io.Serializable {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /**
     * 事件唯一 ID，用于发布和消费幂等去重。
     */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    /**
     * 公共事件类型标识。
     */
    @TableField(value = "event_type")
    private String eventType;

    /**
     * 公共事件 JSON，使用固定版本结构，仅包含允许公开的元数据，不含正文或凭据。
     */
    @TableField(value = "event")
    private String event;

    /**
     * 事件发生时间的 Unix 秒数，与 occurred_nano 配对。
     */
    @TableField(value = "occurred_sec")
    private Long occurredSec;

    /**
     * 事件发生时间的秒内纳秒数，范围 0 至 999999999。
     */
    @TableField(value = "occurred_nano")
    private Integer occurredNano;

    /**
     * 发布状态：0 待发布，1 租约领取中，2 已发布到 Redis；已发布不代表已消费。
     */
    @TableField(value = "state")
    private Integer state;

    /**
     * 累计领取次数，初始为 0，每次领取递增。
     */
    @TableField(value = "attempts")
    private Long attempts;

    /**
     * UTC 下次可领取时间：待发布时为重试时间，领取中为租约到期时间，已发布为空。
     */
    @TableField(value = "available_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime availableAt;

    /**
     * 当前领取租约令牌，确认或重试时比较，防止过期发布者修改记录。
     */
    @TableField(value = "lease_token", updateStrategy = FieldStrategy.ALWAYS)
    private String leaseToken;

    /**
     * Redis 接收事件并确认后记录的 UTC 发布时间，尚未确认时为空。
     */
    @TableField(value = "published_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime publishedAt;
}
