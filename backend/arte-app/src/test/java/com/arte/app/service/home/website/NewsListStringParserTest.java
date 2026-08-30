package com.arte.app.service.home.website;

import cn.hutool.core.collection.CollUtil;
import com.arte.app.pojo.home.website.NewsVo;
import com.arte.app.pojo.home.website.WebsiteInfoDto;
import com.arte.core.enums.HttpRequestTypeEnum;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class NewsListStringParserTest {

    // 未央网 国际快讯 https://www.weiyangx.com/category/express
    // 未央网 金融市场 https://www.weiyangx.com/tag/%E9%87%91%E8%9E%8D%E5%B8%82%E5%9C%BA
    // 未央网 银行 https://www.weiyangx.com/tag/%E9%93%B6%E8%A1%8C
    // 未央网 保险 https://www.weiyangx.com/tag/%E4%BF%9D%E9%99%A9
    // 未央网 证券 https://www.weiyangx.com/tag/%E8%AF%81%E5%88%B8
    // 未央网 互联网小贷 https://www.weiyangx.com/tag/%E4%BA%92%E8%81%94%E7%BD%91%E5%B0%8F%E8%B4%B7
    // 未央网 消费金融 https://www.weiyangx.com/tag/%E6%B6%88%E8%B4%B9%E9%87%91%E8%9E%8D
    // 未央网 数字货币 https://www.weiyangx.com/tag/%E6%95%B0%E5%AD%97%E8%B4%A7%E5%B8%81
    // 未央网 第三方支付 https://www.weiyangx.com/tag/%E7%AC%AC%E4%B8%89%E6%96%B9%E6%94%AF%E4%BB%98
    // 未央网 征信 https://www.weiyangx.com/tag/%E5%BE%81%E4%BF%A1
    // 未央网 金融信息服务 https://www.weiyangx.com/category/financial-information-service
    // 未央网 人工智能 https://www.weiyangx.com/tag/%E4%BA%BA%E5%B7%A5%E6%99%BA%E8%83%BD
    // 未央网 大数据 https://www.weiyangx.com/tag/%E5%A4%A7%E6%95%B0%E6%8D%AE
    // 未央网 区块链 https://www.weiyangx.com/tag/%E5%8C%BA%E5%9D%97%E9%93%BE
    // 未央网 云技术 https://www.weiyangx.com/tag/%E4%BA%91%E6%8A%80%E6%9C%AF
    // 未央网 物联网 https://www.weiyangx.com/tag/%E7%89%A9%E8%81%94%E7%BD%91
    // 未央网 5G通讯 https://www.weiyangx.com/tag/5G%E9%80%9A%E8%AE%AF
    // 未央网 互联网经济 https://www.weiyangx.com/category/internet-economy
    // 未央网 国际快讯 https://www.weiyangx.com/tag/%E9%87%91%E8%9E%8D%E5%B8%82%E5%9C%BA
    @Test
    public void parseNews01() {
        WebsiteInfoDto config = new WebsiteInfoDto();
        config.setInformationUrl("https://www.weiyangx.com/category/internet-economy");
        config.setDataPath("<script>window.__INITIAL_STATE__=;</script>");
        config.setFieldMapping("post_id||id, post_title||title,post_content||summary");
        config.setFormatter("https://www.weiyangx.com/${id}.html");
        config.setLogoUrl("https://img.weiyangx.com/wytimgs/logo.png");
        config.setRequestType(HttpRequestTypeEnum.GET);
        NewsListStringParser parser = new NewsListStringParser();
        List<NewsVo> list = parser.parseNews(config);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
    }

    // 36氪 财经 https://36kr.com/information/ccs
    // 36氪 AI https://36kr.com/information/AI
    // 36氪 创新 https://36kr.com/information/innovate
    // 36氪 科技 https://36kr.com/information/technology
    // 36氪 自助报道 https://36kr.com/information/aireport
    // 36氪 汽车 https://36kr.com/information/travel
    @Test
    public void parseNews02() {
        WebsiteInfoDto config = new WebsiteInfoDto();
        config.setInformationUrl("https://36kr.com/information/ccs");
        // 以 # 作为分隔符
        config.setDataPath("window.initialState=;</script>||information.informationList.itemList");
        config.setFieldMapping("templateMaterial.itemId||id, templateMaterial.widgetTitle||title,templateMaterial.summary||summary");
        config.setFormatter("https://36kr.com/p/${id}");
        config.setLogoUrl("https://staticx.36krcdn.com/36kr-web/static/ic_36kr_logo_68_38@2x.187cd924.png");
        config.setRequestType(HttpRequestTypeEnum.GET);
        config.setRequestHeaders("Accept||*/*##Origin||https://36kr.com##Referer||https://36kr.com/##user-agent||Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36");
        config.setModuleUrl("https://36kr.com/information/ccs/");
        config.setProxy(Boolean.FALSE);
        NewsListStringParser parser = new NewsListStringParser();
        List<NewsVo> list = parser.parseNews(config);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
    }

    // 36氪 浙江 https://36kr.com/local/zhejiang
    @Test
    public void parseNews03() {
        WebsiteInfoDto config = new WebsiteInfoDto();
        config.setInformationUrl("https://36kr.com/local/zhejiang");
        // 以 # 作为分隔符
        config.setDataPath("window.initialState=;</script>||localStation.data.flow.itemList");
        config.setFieldMapping("templateMaterial.itemId||id, templateMaterial.widgetTitle||title,templateMaterial.summary||summary");
        config.setFormatter("https://36kr.com/p/${id}");
        config.setLogoUrl("https://staticx.36krcdn.com/36kr-web/static/ic_36kr_logo_68_38@2x.187cd924.png");
        config.setRequestType(HttpRequestTypeEnum.GET);
        config.setRequestHeaders("Accept||text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7##Origin||https://36kr.com##Referer||https://36kr.com/local/zhejiang##user-agent||Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36");
        config.setModuleUrl("https://36kr.com/information/ccs/");
        config.setProxy(Boolean.FALSE);
        NewsListStringParser parser = new NewsListStringParser();
        List<NewsVo> list = parser.parseNews(config);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
    }
}