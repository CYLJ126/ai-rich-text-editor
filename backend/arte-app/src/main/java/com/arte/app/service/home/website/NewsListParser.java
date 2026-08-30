package com.arte.app.service.home.website;

import cn.hutool.core.text.CharSequenceUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.ReflectUtil;
import com.arte.app.common.constant.WebsiteNewsParamHandler;
import com.arte.app.pojo.home.website.NewsVo;
import com.arte.app.pojo.home.website.WebsiteInfoDto;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 请求各网站新闻列表后，将响应结果解析成 {@link NewsVo} 对象
 *
 * @author zhangsc
 * @since 2025/4/10 14:14
 */
@Slf4j
public abstract class NewsListParser {

    @Value("${http.proxy.host}")
    protected String proxyHost;

    @Value("${http.proxy.port}")
    protected int proxyPort;

    @Resource
    protected ObjectMapper objectMapper;

    public abstract List<NewsVo> parseNews(WebsiteInfoDto websiteInfo);

    protected String getUrl(NewsVo news, WebsiteInfoDto websiteInfo) {
        if (websiteInfo == null || news == null) {
            return null;
        }
        if (CharSequenceUtil.isBlank(websiteInfo.getFormatter())) {
            return news.getUrl();
        }

        // 使用线程安全的StringBuilder处理URL构建
        StringBuilder result = new StringBuilder();
        Pattern pattern = Pattern.compile("\\$\\{(.+?)\\}");
        Matcher matcher = pattern.matcher(websiteInfo.getFormatter());

        try {
            while (matcher.find()) {
                String fieldName = matcher.group(1);
                String replacement;
                // # 表示有格式处理，# 前面表示内容，# 后面表示处理格式说明，如 ${time#YYYYMMDD} 表示取 NewsVo 的 time 字段，格式化为 YYYYMMDD 格式
                if (CharSequenceUtil.contains(fieldName, "#")) {
                    String[] split = fieldName.split("#");
                    String value = (String) ReflectUtil.getFieldValue(news, split[0]);
                    replacement = WebsiteNewsParamHandler.transfer(split[1], value);
                } else {
                    Object value = ReflectUtil.getFieldValue(news, fieldName);
                    replacement = ObjectUtil.isNull(value) ? CharSequenceUtil.EMPTY : value.toString();
                }
                matcher.appendReplacement(result, replacement);
            }
            matcher.appendTail(result);

            return result.toString();
        } catch (Exception e) {
            log.error("解析对象值失败，配置【{}】，新闻对象【{}】", websiteInfo, news, e);
        }
        return CharSequenceUtil.EMPTY;
    }

    protected Map<String, String> parseFieldMapping(String fieldMapping) {
        Map<String, String> map = new HashMap<>();
        if (fieldMapping == null || fieldMapping.isEmpty()) {
            return map;
        }

        String[] pairs = fieldMapping.split(",");
        for (String pair : pairs) {
            String[] kv = pair.split("\\|\\|");
            if (kv.length == 2) {
                map.put(kv[0].trim(), kv[1].trim());
            }
        }
        return map;
    }

}
