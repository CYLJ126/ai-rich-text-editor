package com.arte.ainew.spi.model;

import com.arte.ainew.pojo.model.ModelPublications.Release;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.reference.DefinitionRef;

import java.util.List;
import java.util.Optional;

/**
 * 资源发布证据持久化
 * <p>
 * 只保存不可变记录，不判断使用资格。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 20:55 ✾
 */
public interface ModelPublicationStore {
    /**
     * 读取精确绑定证据，不存在或范围不符时为空。
     *
     * @param scope   已确认的主空间
     * @param binding 精确资源绑定
     * @param current 是否要求原可写事务及父范围锁下的当前读
     * @return 不可变发布证据
     */
    Optional<Release> find(Scope scope, DefinitionRef binding, boolean current);

    /**
     * 有界读取同一空间的个人版本，用于目录装配，不自动授予权限。
     *
     * @param scope   已确认的主空间
     * @param current 是否使用父范围门控下的当前读
     * @return 不超过 256 个固定版本；超过上限须拒绝而非静默截断
     */
    List<Release> list(Scope scope, boolean current);

    /**
     * 在原发布事务内插入证据，与模型版本、回执及事件一起提交。
     *
     * @param release 已核验的固定版本证据
     * @return 唯一插入是否成功
     */
    boolean insert(Release release);
}
