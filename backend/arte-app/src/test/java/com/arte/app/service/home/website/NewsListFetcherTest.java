package com.arte.app.service.home.website;

import com.arte.app.pojo.home.website.WebsiteInfoDto;
import com.arte.core.enums.HttpRequestTypeEnum;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;

public class NewsListFetcherTest {

    @Test
    public void fetchNews() {
        WebsiteInfoDto websiteInfo = new WebsiteInfoDto();
        websiteInfo.setInformationUrl("https://cms-api.csdn.net/v1/web_home/select_content?componentIds=www-selected-article&time=${unix-milli}");
        websiteInfo.setRequestType(HttpRequestTypeEnum.GET);
        String result = NewsListFetcher.fetchNews(websiteInfo, "", 7890, 10000);
        assertNotNull(result);
    }
}