package com.arte.app.service.home.website;

import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.arte.app.pojo.home.website.WebsiteInfoDto;
import com.arte.core.enums.HttpRequestTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;

import java.util.Date;

/**
 * @author zhangsc
 * @since 2026/6/4 15:05
 */
@Slf4j
public class WebsiteInfoServiceImplTest {

    @Test
    public void testLogoGetter() {
        String logoUrl = "https://static.segmentfault.com/main_site_next/prod/_next/static/media/logo-b.1ef53c6e.svg";
        WebsiteInfoDto websiteInfo = new WebsiteInfoDto();
        websiteInfo.setLogoUrl(logoUrl);
        websiteInfo.setRequestType(HttpRequestTypeEnum.GET);
        websiteInfo.setRequestHeaders("referer||https://segmentfault.com/");
        HttpRequest httpRequest = NewsListFetcher.buildHttpRequest(HttpRequestTypeEnum.GET, logoUrl, websiteInfo.getRequestHeaders(), "", false, "", 7890, 6000);
        try (HttpResponse response = httpRequest.execute()) {
            byte[] imageBytes = response.bodyBytes();
            FileUtil.writeBytes(imageBytes, "d:/" + DateUtil.format(new Date(), DatePattern.PURE_DATETIME_FORMAT));
        } catch (Exception e) {
            log.error("获取网站 logo 失败", e);
        }
    }
}
