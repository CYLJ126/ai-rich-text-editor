package com.nip.app.service.home.website;

import cn.hutool.core.text.CharSequenceUtil;
import cn.hutool.core.util.ReflectUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.nip.app.pojo.home.website.NewsVo;
import com.nip.app.pojo.home.website.WebsiteInfoDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 请求各网站新闻列表后，将响应结果解析成 {@link NewsVo} 对象
 *
 * @author zhangsc
 * @since 2025/4/20 14:14
 */
@Slf4j
@Service
public class NewsListStringParser extends NewsListParser {

    @Override
    public List<NewsVo> parseNews(WebsiteInfoDto config) {
        List<NewsVo> result = new ArrayList<>();
        try {
            String content = NewsListFetcher.fetchNews(config, proxyHost, proxyPort, 10000);
            String[] split = config.getDataPath().split("\\|\\|");
            String[] dataPath = split[0].split(";");
            content = CharSequenceUtil.subBetween(content, dataPath[0], dataPath[1]);
            String jsonPath = split.length < 2 ? "" : split[1];
            JSONArray newsArray = CharSequenceUtil.isEmpty(jsonPath) ? JSONUtil.parseArray(content) : (JSONArray) JSONUtil.parse(content).getByPath(jsonPath);
            Map<String, String> fieldMap = parseFieldMapping(config.getFieldMapping());
            for (int i = 0; i < newsArray.size(); i++) {
                JSONObject jsonObj = newsArray.getJSONObject(i);
                NewsVo news = new NewsVo();
                fieldMap.forEach((key, value) -> {
                    Object fieldValue = jsonObj.getByPath(key);
                    if (Objects.nonNull(fieldValue)) {
                        ReflectUtil.setFieldValue(news, value, fieldValue);
                    }
                });
                if (CharSequenceUtil.isBlank(news.getSummary())) {
                    news.setSummary(news.getTitle());
                }
                news.setUrl(getUrl(news, config));
                result.add(news);
            }
        } catch (Exception e) {
            log.error("解析 html 出错，配置【{}】", config, e);
        }
        return result;
    }
}
