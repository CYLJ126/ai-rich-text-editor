package com.arte.base.api.admission;

import com.arte.base.model.admission.AdmissionAttempt;
import com.arte.base.model.admission.AdmissionRequest;

/**
 * 通用执行准入。
 *
 * <p>管理并发、速率、队列优先级和准入；采用有界等待，AI 费用预留与结算由 AI 层负责。
 *
 * <p>设计依据：ARTE 顶层需求及设计，顶层接口 §1.3。
 * 准入不证明预算已预留、执行已耐久受理或业务已经完成。
 */
public interface AdmissionController {
    /**
     * 有界等待；提供者故障不得默认放行，等待句柄的结果不能由调用方伪造。
     */
    AdmissionAttempt acquire(AdmissionRequest request);
}
