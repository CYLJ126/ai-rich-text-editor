package com.arte.app.api.dw;

import com.baomidou.mybatisplus.extension.service.IService;
import com.arte.app.pojo.dw.StickyDto;
import com.arte.app.pojo.dw.param.StickyParam;
import com.arte.core.pojo.PageView;

/**
 * <p>
 * 便笺表 服务类
 * </p>
 *
 * @author zhangsc
 * @since 2025-06-20
 */
public interface StickyService extends IService<StickyDto> {

    /**
     * 根据条件查询便笺 ID 列表
     *
     * @param param 查询参数
     * @return 列表
     */
    PageView<StickyDto> listStickies(StickyParam param);

    /**
     * 获取便笺详情
     *
     * @param param 待查询的便笺记录
     * @return 获取结果
     */
    StickyDto getStickyById(StickyDto param);

    /**
     * 添加空便笺
     *
     * @param param 待添加的便笺记录
     * @return 添加后带 ID 的便笺
     */
    StickyDto addSticky(StickyDto param);

    /**
     * 更新便笺标题、 内容
     *
     * @param param 待更新的便笺记录
     * @return 添加结果
     */
    boolean updateSticky(StickyDto param);

    /**
     * 改变便笺宽度、高度
     *
     * @param param 待更新的便笺记录
     * @return 添加结果
     */
    boolean resizeSticky(StickyDto param);

    /**
     * 排序便笺
     *
     * @param param 待更新的便笺记录
     * @return 添加结果
     */
    boolean orderSticky(StickyDto param);

    /**
     * 折叠或展开便笺
     *
     * @param param 待更新的便笺记录
     * @return 添加结果
     */
    boolean foldSticky(StickyDto param);

    /**
     * 更改主题色
     *
     * @param param 待更新的便笺记录
     * @return 添加结果
     */
    boolean switchThemeColor(StickyDto param);

    /**
     * 删除便笺
     *
     * @param param 待删除的便笺记录
     * @return 添加结果
     */
    boolean deleteSticky(StickyDto param);

}
