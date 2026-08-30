package com.nip.app.service.home.website;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.text.CharSequenceUtil;
import cn.hutool.core.text.StrPool;
import cn.hutool.core.util.ReflectUtil;
import cn.hutool.core.util.StrUtil;
import com.nip.app.pojo.home.website.NewsVo;
import com.nip.app.pojo.home.website.WebsiteInfoDto;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.util.*;

/**
 * @author zhangsc
 * @since 2025/4/16 19:48
 */
@Slf4j
@Service
@Primary
public class NewsListHtmlParser extends NewsListParser {

    @Override
    public List<NewsVo> parseNews(WebsiteInfoDto config) {
        Document doc;
        try {
            Connection conn = Jsoup.connect(config.getInformationUrl()).followRedirects(Boolean.TRUE).timeout(10000);
            if (StrUtil.startWith(config.getInformationUrl(), "https")) {
                SSLSocketFactory sslSocketFactory = NewsListFetcher.getSSLSocketFactory();
                if (sslSocketFactory != null) {
                    conn = conn.sslSocketFactory(sslSocketFactory);
                }
            }
            if (StrUtil.isNotBlank(proxyHost)) {
                conn = conn.proxy(proxyHost, proxyPort);
            }
            doc = conn.get();
        } catch (IOException e) {
            log.error("获取新闻列表失败，配置【{}】", config, e);
            return Collections.emptyList();
        }

        try {
            String dataPath = config.getDataPath();
            if (dataPath.contains(";")) {
                return parseJsonData(doc, config);
            } else {
                return parseHtmlData(doc, config);
            }
        } catch (Exception e) {
            log.error("解析 html 出错，配置【{}】", config, e);
            return Collections.emptyList();
        }
    }

    protected List<NewsVo> parseJsonData(Document doc, WebsiteInfoDto config) {
        String[] parts = config.getDataPath().split(";", 2);
        String htmlSelector = parts[0];
        String jsonPath = parts[1].replace('.', '/');
        jsonPath = StrPool.SLASH + jsonPath;

        Elements scriptElements = doc.select(htmlSelector);
        if (scriptElements.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            String jsonText = Objects.requireNonNull(scriptElements.first()).html();
            JsonNode rootNode = objectMapper.readTree(jsonText);
            JsonNode dataNode = rootNode.at(jsonPath);

            if (!dataNode.isArray()) {
                return Collections.emptyList();
            }

            Map<String, String> fieldMap = parseFieldMapping(config.getFieldMapping());
            List<NewsVo> newsList = new ArrayList<>();

            dataNode.forEach(node -> {
                NewsVo news = new NewsVo();
                fieldMap.forEach((source, target) -> {
                    List<String> split = StrUtil.split(source, ".");
                    JsonNode valueNode = node;
                    for (String s : split) {
                        valueNode = valueNode.get(s);
                    }
                    if (valueNode != null) {
                        ReflectUtil.setFieldValue(news, target, valueNode.asString());
                    }
                });
                if (CharSequenceUtil.isBlank(news.getSummary())) {
                    news.setSummary(news.getTitle());
                }
                news.setUrl(getUrl(news, config));
                newsList.add(news);
            });

            return newsList;
        } catch (Exception e) {
            log.error("解析 json 出错，配置【{}】", config);
            return Collections.emptyList();
        }
    }

    /**
     * 规则：
     * a：什么也不带，表示标签；搜索 <a> 标签；
     * .about：带点表示标签类名；搜索类名为 .about 的标签；
     * a#href：带 # 表示属性，取 <a> 标签的 href 属性为值；
     * a(text)：带 (text) 表示取标签内容；
     * h2>a#href：带 > 表示取子标签；取 h2 下面的 a 标签的 href 属性；
     * h2>a#href||url,a(text)||title,.about(text)||summary：|| 表示取值与字段映射关系，, 表示两个字段分隔；取 h2 下面的 a 标签的 href 属性映射成 url 字段，取 a 标签的内容映射成 title 字段，取.about 的内容映射成 summary 字段；
     *
     * @param doc    html 文档
     * @param config 配置，其中 fieldMapping 字段会映射成对应规则
     * @return 新闻列表
     */
    protected List<NewsVo> parseHtmlData(Document doc, WebsiteInfoDto config) {
        // 由于英文逗号被用于分隔不同字段取值，所以用或表示可选多个标签
        Elements elements = doc.select(config.getDataPath().replace("或", ","));
        Map<String, String> fieldMap = parseFieldMapping(config.getFieldMapping());
        List<NewsVo> newsList = new ArrayList<>();

        for (Element element : elements) {
            NewsVo news = new NewsVo();
            fieldMap.forEach((sourceExpr, target) -> {
                String value = extractValueFromElement(element, sourceExpr);
                ReflectUtil.setFieldValue(news, target, value);
            });
            if (CharSequenceUtil.isBlank(news.getSummary())) {
                news.setSummary(news.getTitle());
            }
            if (!CharSequenceUtil.isAllBlank(news.getTitle(), news.getSummary())) {
                newsList.add(news);
            }
            news.setUrl(getUrl(news, config));
        }

        return newsList;
    }

    private String extractValueFromElement(Element element, String sourceExpr) {
        if (CharSequenceUtil.contains(sourceExpr, ">")) {
            element = element.selectFirst(CharSequenceUtil.subBefore(sourceExpr, ">", false));
            if (Objects.isNull(element)) {
                return CharSequenceUtil.EMPTY;
            }
            return extractValueFromElement(element, CharSequenceUtil.subAfter(sourceExpr, ">", false));
        }

        if (CharSequenceUtil.contains(sourceExpr, "(text)")) {
            String selector = CharSequenceUtil.subBefore(sourceExpr, "(text)", false);
            if (CharSequenceUtil.isNotBlank(selector)) {
                Element targetElement = element.selectFirst(selector.replace("或", ","));
                // 如果要取的标签不存在，退化为取当前标签的内容
                element = Objects.isNull(targetElement) ? element : targetElement;
            }
            return element.text();
        }

        // 尝试获取属性
        if (CharSequenceUtil.contains(sourceExpr, "#")) {
            List<String> split = CharSequenceUtil.split(sourceExpr, "#");
            if (CollUtil.isEmpty(split) || split.size() < 2) {
                return CharSequenceUtil.EMPTY;
            }
            element = element.selectFirst(split.get(0));
            if (Objects.isNull(element)) {
                return CharSequenceUtil.EMPTY;
            }
            String attrValue = element.attr(split.get(1));
            if (!attrValue.isEmpty()) {
                return attrValue;
            }
        }
        return CharSequenceUtil.EMPTY;
    }

}
