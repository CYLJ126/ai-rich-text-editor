package com.arte.base.model.admission;

import com.arte.base.validation.ContractChecks;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 有界等待句柄；结果只读，取消只作用于尚未授予许可的等待，不释放已在执行的工作。
 */
public final class AdmissionAttempt {
    private final CompletableFuture<AdmissionPermit> result;

    /**
     * 由提供者创建并持有 result 的写入权。
     */
    public AdmissionAttempt(CompletableFuture<AdmissionPermit> result) {
        this.result = ContractChecks.required(result, "result");
    }

    public CompletionStage<AdmissionPermit> completion() {
        return result.minimalCompletionStage();
    }

    public boolean cancelWaiting() {
        return result.cancel(false);
    }
}
