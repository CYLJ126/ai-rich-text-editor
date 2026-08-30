package com.nip.app.service.home.website;

import com.nip.app.pojo.home.website.NewsVo;
import com.nip.app.pojo.home.website.WebsiteInfoDto;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.StringEscapeUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 被转义内容的解析，如中国社会科学院金融研究所支付清算研究中心新闻列表解析
 * 1. 非 json 非 html，而是请求一个地址，获得转义后的文本内容，并从中解析，文本内容形如：
 * document.write('\r\n\r\n    \u003cinput type=\"hidden\" id=\"pageinfo\"\r\n           value=\"1435494\"\r\n           data-type=\"1\"\r\n           data-device=\"Pc\"\r\n           data-entityid=\"1435494\" /\u003e\r\n    \u003cinput id=\"txtDeviceSwitchEnabled\" value=\"show\" type=\"hidden\" /\u003e\r\n\r\n    \u003cscript type=\"text/javascript\"\u003e\r\n        $(function() {\r\n\r\n        if (\"False\"==\"True\") {\r\n
 *
 * @author zhangsc
 * @since 2025/5/23 14:46
 */
@Slf4j
@Service
public class NewsListEscapeStrParser extends NewsListHtmlParser {

    @Override
    public List<NewsVo> parseNews(WebsiteInfoDto websiteInfo) {
        String content = NewsListFetcher.fetchNews(websiteInfo, "", 7890, 10000);
        // 步骤1：处理Java Unicode转义（如 \u003c -> <）
        content = StringEscapeUtils.unescapeJava(content);
        // 步骤2：处理HTML实体转义（如 &amp; -> &）
        content = StringEscapeUtils.unescapeHtml4(content);
        Document doc = Jsoup.parse(content);
        return parseHtmlData(doc, websiteInfo);
    }
}
