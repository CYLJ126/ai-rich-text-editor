package com.arte.app.api.home;

import com.baomidou.mybatisplus.extension.service.IService;
import com.arte.app.pojo.home.website.WebsiteInfoDto;

import java.util.List;

/**
 * <p>
 * 资讯网站信息 服务类
 * </p>
 *
 * @author zhangsc
 * @since 2025-04-12
 */
public interface WebsiteInfoService extends IService<WebsiteInfoDto> {

    /**
     * 按标签类型查询归属该标签的所有网站的最新新闻列表并返回。
     *
     * @param param 请求参数，取 type 字段（即标签“新闻资讯”下的子标签）
     * @return 新闻资讯列表
     */
    List<WebsiteInfoDto> listByType(WebsiteInfoDto param);

    /**
     * 重新加载新闻
     */
    Boolean refreshNews();

    /**
     * 获取 Logo
     *
     * @param param 请求参数
     * @return Logo 字节内容
     */
    byte[] getLogoImg(WebsiteInfoDto param);
}
