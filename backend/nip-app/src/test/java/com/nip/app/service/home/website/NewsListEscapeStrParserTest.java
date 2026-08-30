package com.nip.app.service.home.website;

import cn.hutool.core.collection.CollUtil;
import com.nip.app.pojo.home.website.NewsVo;
import com.nip.app.pojo.home.website.WebsiteInfoDto;
import com.nip.core.enums.HttpRequestTypeEnum;
import com.nip.core.serialize.SerializerFactory;
import org.junit.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.Assert.assertTrue;

public class NewsListEscapeStrParserTest {

    private final ObjectMapper objectMapper = SerializerFactory.buildJsonMapperWithoutTypeProperty();

    // 中国社会科学院金融研究所支付清算研究中心 首页 http://www.rcps.org.cn/
    @Test
    public void parse01() {
        WebsiteInfoDto config = new WebsiteInfoDto();
        config.setModuleUrl("http://www.rcps.org.cn/");
        config.setLogoUrl("http://nwzimg.wezhan.cn/contents/sitefiles2058/10290306/images/40217370.png");
        config.setInformationUrl("https://nwzimg.wezhan.cn/pubsf/10290/10290306/cdn-static-pages/pages/pc/1435494_zh-cn.html.Body.js?version=20250506124634");
        config.setFormatter("http://www.rcps.org.cn/${url}");
        config.setRequestType(HttpRequestTypeEnum.GET);
        config.setRequestHeaders("referer||http://www.rcps.org.cn/##user-agent||Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36");
        config.setDataPath("a[href~=/(newsinfo|filedownload)/]");
        config.setFieldMapping("a#href||url,a(text)||title");
        NewsListEscapeStrParser parser = new NewsListEscapeStrParser();
        List<NewsVo> list = parser.parseNews(config);
        assertTrue(CollUtil.isNotEmpty(list));
    }
}