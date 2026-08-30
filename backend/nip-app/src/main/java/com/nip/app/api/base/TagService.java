package com.nip.app.api.base;

import com.baomidou.mybatisplus.extension.service.IService;
import com.nip.app.pojo.base.TagDto;
import com.nip.app.pojo.base.param.TagParam;

import java.util.List;
import java.util.Map;

/**
 * <p>
 * 标签表 服务类
 * </p>
 *
 * @author zhangsc
 * @since 2025-02-08
 */
public interface TagService extends IService<TagDto> {

    /**
     * 查询对应的子标签树
     *
     * @param param 查询参数
     * @return 标签树
     */
    List<TagDto> listRecursive(TagParam param);

    /**
     * 查询对应的父标签树
     *
     * @param param 查询参数
     * @return 标签树
     */
    List<TagDto> listAntiRecursive(TagParam param);

    /**
     * 根据顶级标签名查找对应的标签树。
     * 使用条件：只有查找某个顶级标签时才可这样做，且顶级标签的名字互不重复，顶级标签的父 ID 为 null。
     *
     * @param name 顶级标签名
     * @return 按 ID 排序后的标签树
     */
    TagDto listRecursiveByRootName(String name);

    /**
     * 根据父 ID 查询其下所有子标签的最大顺序。
     * 如果父 ID 没有子标签，则返回 0。
     *
     * @param fatherId 父 ID
     * @return 其下所有子标签的最大顺序
     */
    Integer findMaxOrder(Integer fatherId);

    /**
     * 排序
     *
     * @param list  待排序标签
     * @param begin 排序后第一个标签的顺序号
     * @param isAsc true-升序；false-降序；
     * @return true-成功；false-失败；
     */
    Boolean reorderTags(List<TagDto> list, Integer begin, Boolean isAsc);

    /**
     * 递归删除当前标签和下级标签
     *
     * @param id 当前标签 ID
     * @return true-成功；false-失败；
     */
    Boolean removeRecursive(Integer id);

    /**
     * 获取标签映射集，Map<标签 ID，标签对象>
     *
     * @param param 请求参数
     * @return 标签映射集
     */
    Map<Integer, TagDto> getTagMap(TagParam param);

    /**
     * 获取日课标签映射集，Map<标签 ID，标签对象>
     *
     * @return 标签映射集
     */
    Map<Integer, TagDto> getDailyWorkTagMap();

    /**
     * 刷新标签缓存
     */
    void refresh();

}
