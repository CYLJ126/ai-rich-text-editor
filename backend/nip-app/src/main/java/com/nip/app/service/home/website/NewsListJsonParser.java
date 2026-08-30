package com.nip.app.service.home.website;

import cn.hutool.core.text.CharSequenceUtil;
import cn.hutool.core.util.ReflectUtil;
import cn.hutool.core.util.StrUtil;
import com.nip.app.pojo.home.website.FieldMapping;
import com.nip.app.pojo.home.website.NewsVo;
import com.nip.app.pojo.home.website.WebsiteInfoDto;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 请求各网站新闻列表后，将响应结果解析成 {@link NewsVo} 对象
 *
 * @author zhangsc
 * @since 2025/4/10 14:14
 */
@Slf4j
@Service
public class NewsListJsonParser extends NewsListParser {

    @Resource
    protected ObjectMapper objectMapper;

    @Override
    public List<NewsVo> parseNews(WebsiteInfoDto websiteInfo) {
        try {
            // 获取
            String content = NewsListFetcher.fetchNews(websiteInfo, proxyHost, proxyPort, 10000);
            // 解析
            List<NewsVo> newsList = parseJson(content, websiteInfo);
            if (newsList.size() > 50) {
                newsList = newsList.subList(0, 50);
            }
            //组装
            newsList.forEach(item -> {
                item.setUrl(getUrl(item, websiteInfo));
                if (CharSequenceUtil.isNotBlank(item.getSummary()) && item.getSummary().length() > 90) {
                    item.setSummary(CharSequenceUtil.sub(item.getSummary(), 0, 90) + "...");
                }
            });
            // 上面 subList() 方法会返回 java.util.ArrayList$SubList，直接返回的话，在从 Redis 中反序列化时会报错
            return new ArrayList<>(newsList);
        } catch (IOException e) {
            log.error("解析出错，配置【{}】", websiteInfo, e);
        }
        return Collections.emptyList();
    }

    /**
     * 解析，如果有多层，用 / 分隔层级节点，如果要取某层中的列表，再对列表中的每个元素取列表，则用 > 连接
     * /data/www-info-list-new/info/list：取该 json 路径下的 list 节点为列表
     * /data>/docInfoVOList：取 /data 路径下的列表，再对每个列表取 /docInfoVOList 节点为列表
     *
     * @param json        内容
     * @param websiteInfo 配置
     * @return 新闻列表
     * @throws IOException IO 异常
     */
    public List<NewsVo> parseJson(String json, WebsiteInfoDto websiteInfo) throws IOException {
        JsonNode rootNode = objectMapper.readTree(json);

        // 检查条件
        if (CharSequenceUtil.isNotBlank(websiteInfo.getConditionPath()) &&
                !checkCondition(rootNode, websiteInfo.getConditionPath(), websiteInfo.getConditionValue())) {
            return Collections.emptyList();
        }

        List<NewsVo> result = new ArrayList<>();
        analyzeRecursive(rootNode, websiteInfo, websiteInfo.getDataPath(), result);
        return result;
    }

    private void analyzeRecursive(JsonNode rootNode, WebsiteInfoDto websiteInfo, String path, List<NewsVo> result) {
        List<String> split = CharSequenceUtil.split(path, '>', 2);
        if (split.size() == 2) {
            JsonNode subNode = rootNode.at(split.get(0));
            if (subNode.isArray()) {
                for (JsonNode itemNode : subNode) {
                    analyzeRecursive(itemNode, websiteInfo, split.get(1), result);
                }
            }
        } else {
            result.addAll(analyzeList(rootNode, websiteInfo, split.get(0)));
        }
    }

    private List<NewsVo> analyzeList(JsonNode rootNode, WebsiteInfoDto websiteInfo, String dataPath) {
        try {
            // 获取数据列表
            JsonNode dataListNode = CharSequenceUtil.equals(dataPath, "/") ? rootNode : rootNode.at(dataPath);
            if (!dataListNode.isArray()) {
                return Collections.emptyList();
            }
            websiteInfo.mapFields();
            return mapDataList(dataListNode, websiteInfo.getFieldMappings());
        } catch (Exception e) {
            log.error("解析出错，节点【{}】，配置【{}】", rootNode, websiteInfo, e);
            return Collections.emptyList();
        }
    }

    private boolean checkCondition(JsonNode rootNode, String conditionPath, String conditionValue) {
        if (!CharSequenceUtil.startWith(conditionPath, "/")) {
            // 数据库中为方便配置，不加 /，但取值时需要添加
            conditionPath = StrUtil.SLASH + conditionPath;
        }
        JsonNode conditionNode = rootNode.at(conditionPath);
        if (conditionNode.isMissingNode()) {
            return false;
        }
        String actualValue = conditionNode.asText();
        return actualValue.equals(conditionValue);
    }

    private List<NewsVo> mapDataList(JsonNode dataListNode, List<FieldMapping> mappings) {
        List<NewsVo> result = new ArrayList<>();
        for (JsonNode itemNode : dataListNode) {
            NewsVo newsVo = new NewsVo();
            for (FieldMapping mapping : mappings) {
                JsonNode valueNode = itemNode.at(mapping.getSourcePath());
                if (!valueNode.isMissingNode() && !valueNode.isNull()) {
                    ReflectUtil.setFieldValue(newsVo, mapping.getTargetField(), valueNode.asText());
                }
            }
            if (CharSequenceUtil.isBlank(newsVo.getSummary())) {
                newsVo.setSummary(newsVo.getTitle());
            }
            result.add(newsVo);
        }
        return result;
    }
}
