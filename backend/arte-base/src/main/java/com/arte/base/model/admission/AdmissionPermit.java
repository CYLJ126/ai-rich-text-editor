package com.arte.base.model.admission;

/**
 * 仅在实际任务已经退出后释放；重复 close 无副作用。分布式提供者须另落实租约与失效语义。
 */
public interface AdmissionPermit extends AutoCloseable {
    AdmissionRequest request();

    @Override
    void close();
}
