package com.arte.app.mapper.richtext;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.arte.app.pojo.richtext.CatalogDto;
import com.arte.core.annotations.MybatisParams;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 目录 Mapper
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/5/11 ✾
 **/
@MybatisParams("arte_rt_catalog")
public interface CatalogMapper extends BaseMapper<CatalogDto> {

    /**
     * 根据条件查找目录（含自身及所有子目录）
     */
    List<CatalogDto> listAll(CatalogDto param);

    /**
     * 批量更新排序
     */
    Boolean updateOrder(CatalogDto catalog);

    /**
     * 递归删除当前目录及所有子目录
     */
    Boolean removeRecursive(@Param("id") Integer id);

    /**
     * 按ID列表查询目录
     */
    @MybatisParams(value = "arte_rt_catalog", ignore = true)
    List<CatalogDto> listByIds(@Param("ids") List<Integer> ids);

    /**
     * 查询多个根目录及其所有子孙目录
     */
    @MybatisParams(value = "arte_rt_catalog", ignore = true)
    List<CatalogDto> listDescendantsByRootIds(@Param("rootIds") List<Integer> rootIds);

    /**
     * 查询某目录的所有祖先目录ID（递归CTE，用于权限继承校验）
     */
    @MybatisParams(value = "arte_rt_catalog", ignore = true)
    List<Integer> listAncestorIds(@Param("id") Integer id);

    /**
     * 查询某目录的所有子孙目录ID（递归CTE）
     */
    @MybatisParams(value = "arte_rt_catalog", ignore = true)
    List<Integer> listDescendantIds(@Param("id") Integer id);

    /**
     * 按ID查询目录（不受拦截器影响）
     */
    @MybatisParams(value = "arte_rt_catalog", ignore = true)
    CatalogDto getByIdUnfiltered(@Param("id") Integer id);

    /** 查询当前用户私有目录及全部公共目录 */
    @MybatisParams(value = "arte_rt_catalog", ignore = true)
    List<CatalogDto> listOwnedOrPublicCatalogs(@Param("currentUser") String currentUser);

    /** 查询同级目录中的最大排序值 */
    @MybatisParams(value = "arte_rt_catalog", ignore = true)
    Integer findMaxOrder(@Param("fatherId") Integer fatherId);
}
