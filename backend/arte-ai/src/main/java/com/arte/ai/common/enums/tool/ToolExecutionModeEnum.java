package com.arte.ai.common.enums.tool;

import com.arte.core.enums.MyEnum;
import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

/**
 * 工具执行模式。
 *
 * <p>{@link #BLOCKING} 和 {@link #NON_BLOCKING} 都在当前调用生命周期内返回最终结果；
 * {@link #DEFERRED} 创建可持久化任务并先返回任务句柄，不依赖内存中的 Future
 * 跨越进程或长时间等待。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
@Getter
public enum ToolExecutionModeEnum implements IEnum<String>, MyEnum<String> {

    /**
     * 快速同步执行，通常返回已完成的 CompletionStage。
     */
    BLOCKING("blocking", "快速同步执行"),

    /**
     * 当前调用内非阻塞执行，调用方等待 CompletionStage 后继续。
     */
    NON_BLOCKING("non-blocking", "当前调用内非阻塞执行"),

    /**
     * 持久化后台任务，可跨进程恢复、查询、取消和续跑。
     */
    DEFERRED("deferred", "持久化后台任务"),
    ;

    private final String value;
    private final String description;

    ToolExecutionModeEnum(String value, String description) {
        this.value = value;
        this.description = description;
    }
}
