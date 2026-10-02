package com.arte.ai.model.conversation;

/**
 * 提交种类；重新生成保留被引用的旧提交及结果，不默认再次执行业务写操作。
 */
public enum TurnKind {
    MESSAGE,
    REGENERATION
}
