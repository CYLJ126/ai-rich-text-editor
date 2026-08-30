package com.nip.app.service.home.website;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.text.CharSequenceUtil;
import cn.hutool.core.util.ReflectUtil;
import com.nip.app.pojo.home.website.FieldMapping;
import com.nip.app.pojo.home.website.NewsVo;
import com.nip.app.pojo.home.website.WebsiteInfoDto;
import com.nip.core.enums.HttpRequestTypeEnum;
import com.nip.core.serialize.SerializerFactory;
import lombok.extern.slf4j.Slf4j;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * @author zhangsc
 * @since 2025/4/10 14:18
 */
@Slf4j
@SpringBootTest()
public class NewsListJsonParserTest {

    private final ObjectMapper objectMapper = SerializerFactory.buildJsonMapperWithoutTypeProperty();

    static String getContent(String name) {
        try (InputStream input = NewsListJsonParser.class.getClassLoader().getResourceAsStream("json/website/" + name)) {
            return IoUtil.read(input, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("请求参数解析错误");
        }
    }

    @Test
    public void analyzeCsdn() throws IOException {
        WebsiteInfoDto websiteInfo = new WebsiteInfoDto();
        websiteInfo.setProxy(Boolean.FALSE);
        websiteInfo.setConditionPath("/code");
        websiteInfo.setConditionValue("200");
        websiteInfo.setDataPath("/data/www-info-list-new/info/list");
        websiteInfo.setFieldMappings(List.of(
                new FieldMapping("/itemId", "id"),
                new FieldMapping("/title", "title"),
                new FieldMapping("/url", "url")
        ));

        String content = getContent("csdn.json");

        NewsListJsonParser parser = new NewsListJsonParser();
        ReflectUtil.setFieldValue(parser, "objectMapper", objectMapper);
        List<NewsVo> news = parser.parseJson(content, websiteInfo);

        Assert.assertEquals(2, news.size());
        Assert.assertEquals("146926489", news.get(0).getId());
        Assert.assertEquals("146926507", news.get(1).getId());
    }

    @Test
    public void analyzeMoTianLun() throws IOException {
        WebsiteInfoDto websiteInfo = new WebsiteInfoDto();
        websiteInfo.setProxy(Boolean.FALSE);
        websiteInfo.setDataPath("");
        websiteInfo.setFieldMappings(List.of(
                new FieldMapping("/id", "id")
        ));

        String content = getContent("moTianLun.json");

        NewsListJsonParser parser = new NewsListJsonParser();
        List<NewsVo> news = parser.parseJson(content, websiteInfo);

        Assert.assertEquals(2, news.size());
        Assert.assertEquals("1902901654186373120", news.get(0).getId());
        Assert.assertEquals("1906964860232024064", news.get(1).getId());
    }

    @Test
    public void analyzeJuejin() {
        WebsiteInfoDto websiteInfo = new WebsiteInfoDto();
        websiteInfo.setProxy(Boolean.FALSE);
        websiteInfo.setRequestType(HttpRequestTypeEnum.POST);
        websiteInfo.setConditionPath("/err_no");
        websiteInfo.setConditionValue("0");
        websiteInfo.setDataPath("/data");
        websiteInfo.setFieldMapping("item_info/article_info/article_id||id,item_info/article_info/title||title,item_info/article_info/brief_content||summary");
        websiteInfo.setFormatter("https://juejin.cn/post/${id}");
        websiteInfo.setInformationUrl("https://api.juejin.cn/recommend_api/v1/article/recommend_all_feed?aid=2608&uuid=&spider=0");
        NewsListJsonParser parser = new NewsListJsonParser();
        ReflectUtil.setFieldValue(parser, "objectMapper", objectMapper);
        List<NewsVo> list = parser.parseNews(websiteInfo);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
    }

    // 中国电子银行网 首页 完成
    @Test
    public void parse6() {
        WebsiteInfoDto config = new WebsiteInfoDto();
        config.setProxy(Boolean.FALSE);
        config.setRequestType(HttpRequestTypeEnum.GET);
        config.setConditionPath("/code");
        config.setConditionValue("0");
        config.setInformationUrl("https://www.cebnet.com.cn/json/sy/index.json");
        config.setDataPath("/list");
        config.setFieldMapping("articleLink||url,title||title,summary||summary");
        config.setFormatter("${url#cebnetJsonTransfer}");
        config.mapFields();
        NewsListJsonParser parser = new NewsListJsonParser();
        ReflectUtil.setFieldValue(parser, "objectMapper", objectMapper);
        List<NewsVo> list = parser.parseNews(config);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
    }

    // 中国外汇交易中心
    @Test
    public void parse7() {
        WebsiteInfoDto config = new WebsiteInfoDto();
        config.setProxy(Boolean.FALSE);
        config.setRequestType(HttpRequestTypeEnum.POST);
        config.setInformationUrl("https://www.chinamoney.com.cn/ags/ms/cm-s-notice-query/contents?channelId=2497&pageNo=1&pageSize=20");
        config.setDataPath("/records");
        config.setFieldMapping("draftPath||url,title||title");
        config.setFormatter("https://www.chinamoney.com.cn/${url}");
        config.mapFields();
        NewsListJsonParser parser = new NewsListJsonParser();
        ReflectUtil.setFieldValue(parser, "objectMapper", objectMapper);
        List<NewsVo> list = parser.parseNews(config);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
    }

    // 金管局 完成
    @Test
    public void parse8() {
        WebsiteInfoDto config = new WebsiteInfoDto();
        config.setProxy(Boolean.FALSE);
        config.setRequestType(HttpRequestTypeEnum.GET);
        config.setInformationUrl("https://www.nfra.gov.cn/cn/static/data/DocInfo/SelectItemAndDocByItemPId/data_itemId=914,pageSize=10.json");
        config.setDataPath("/data>/docInfoVOList");
        config.setFieldMapping("docId||id,docTitle||title");
        config.setFormatter("https://www.nfra.gov.cn/cn/view/pages/ItemDetail.html?docId=${id}");
        config.setConditionValue("200");
        config.setConditionPath("/rptCode");
        config.mapFields();
        NewsListJsonParser parser = new NewsListJsonParser();
        ReflectUtil.setFieldValue(parser, "objectMapper", objectMapper);
        List<NewsVo> list = parser.parseNews(config);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
    }

    // 金管局 统计信息 完成
    @Test
    public void parse9() {
        WebsiteInfoDto config = new WebsiteInfoDto();
        config.setProxy(Boolean.FALSE);
        config.setRequestType(HttpRequestTypeEnum.GET);
        config.setInformationUrl("https://www.nfra.gov.cn/cn/static/data/DocInfo/SelectDocByItemIdAndChild/data_itemId=954,pageIndex=1,pageSize=18.json");
        config.setDataPath("/data/rows");
        config.setFieldMapping("docId||id,docTitle||title");
        config.setFormatter("https://www.nfra.gov.cn/cn/view/pages/ItemDetail.html?docId=${id}");
        config.setConditionValue("200");
        config.setConditionPath("/rptCode");
        config.mapFields();
        NewsListJsonParser parser = new NewsListJsonParser();
        ReflectUtil.setFieldValue(parser, "objectMapper", objectMapper);
        List<NewsVo> list = parser.parseNews(config);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
    }

    // 掘金 AICoding 完成
    @Test
    public void parse10() {
        WebsiteInfoDto config = new WebsiteInfoDto();
        config.setProxy(Boolean.FALSE);
        config.setRequestType(HttpRequestTypeEnum.POST);
        config.setConditionPath("/err_no");
        config.setConditionValue("0");
        config.setDataPath("/data");
        config.setInformationUrl("https://api.juejin.cn/content_api/v1/aicoding/content?aid=2608&uuid=&spider=0");
        config.setFieldMapping("article_pack/article_id||id,article_pack/article_info/title||title,article_pack/article_info/brief_content||summary");
        config.setFormatter("https://juejin.cn/post/${id}");
        config.setRequestBody("{\"cursor\":\"\",\"sort_type\":1,\"limit\":10}");
        config.mapFields();
        NewsListJsonParser parser = new NewsListJsonParser();
        ReflectUtil.setFieldValue(parser, "objectMapper", objectMapper);
        List<NewsVo> list = parser.parseNews(config);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
    }

    // 掘金 AI 刷题 完成
    @Test
    public void parse11() {
        WebsiteInfoDto config = new WebsiteInfoDto();
        config.setProxy(Boolean.FALSE);
        config.setRequestType(HttpRequestTypeEnum.POST);
        config.setConditionPath("/err_no");
        config.setConditionValue("0");
        config.setDataPath("/data");
        config.setInformationUrl("https://api.juejin.cn/content_api/v1/code_problem/query_list?aid=2608&uuid=&spider=0");
        config.setFieldMapping("problem_id||id,title||title");
        config.setFormatter("https://www.marscode.cn/practice/0xlewy62lj6exp?problem_id=${id}");
        config.setRequestBody("{\"practice_status\":[],\"cursor\":\"0\",\"keyword\":\"\",\"limit\":50,\"page_no\":1}");
        config.mapFields();
        NewsListJsonParser parser = new NewsListJsonParser();
        ReflectUtil.setFieldValue(parser, "objectMapper", objectMapper);
        List<NewsVo> list = parser.parseNews(config);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
    }

    // 百度开发者中心 人工智能 完成
    @Test
    public void pars12() {
        WebsiteInfoDto config = new WebsiteInfoDto();
        config.setProxy(Boolean.FALSE);
        config.setRequestType(HttpRequestTypeEnum.GET);
        config.setConditionPath("/status");
        config.setConditionValue("200");
        config.setDataPath("/page/result");
        config.setInformationUrl("https://developer.baidu.com/api/bce_developer/article/list?pageNo=1&pageSize=10&tagId=198&orderBy=HOT");
        config.setFieldMapping("id||id,title||title,introduction||summary");
        config.setFormatter("https://developer.baidu.com/article/detail.html?id=${id}");
        config.mapFields();
        NewsListJsonParser parser = new NewsListJsonParser();
        ReflectUtil.setFieldValue(parser, "objectMapper", objectMapper);
        List<NewsVo> list = parser.parseNews(config);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
    }

    // CSDN 人工智能 完成
    @Test
    public void parse13() {
        WebsiteInfoDto websiteInfo = new WebsiteInfoDto();
        websiteInfo.setConditionPath("/code");
        websiteInfo.setConditionValue("200");
        websiteInfo.setDataPath("/data/silkroad-pre-home-list/info");
        websiteInfo.setFieldMapping("extend/url||url,extend/title||title,/extend/desc||summary");
        websiteInfo.setRequestType(HttpRequestTypeEnum.GET);
        websiteInfo.setProxy(Boolean.FALSE);
        websiteInfo.setInformationUrl("https://cms-api.csdn.net/v1/web_home/select_content?componentIds=silkroad-pre-home-list&cate1=ai");
        websiteInfo.mapFields();
        NewsListJsonParser parser = new NewsListJsonParser();
        ReflectUtil.setFieldValue(parser, "objectMapper", objectMapper);
        List<NewsVo> list = parser.parseNews(websiteInfo);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
    }

    // 未央网 推荐 TODO
   /* @Test
    public void parse9() {
        WebsiteInfoDto config = new WebsiteInfoDto();
        config.setRequestType(HttpRequestTypeEnum.POST);
        config.setInformationUrl("https://www.weiyangx.com/wp-admin/admin-ajax.php");
        config.setDataPath("/records");
        config.setFieldMapping("draftPath||url,title||title");
        config.setFormatter("https://www.chinamoney.com.cn/${url}");
        config.mapFields();
        NewsListJsonParser parser = new NewsListJsonParser();
        List<NewsVo> list = parser.parseNews(config);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
    }*/

    // 财联社 头条 https://www.cls.cn/v3/depth/home/assembled/1000?app=CailianpressWeb&os=web&sv=8.7.9&sign=b02d8f7bc4c45eeb3e86904203597da2
    // TODO TOP3 没有取到，在不同节点
    @Test
    public void parse14() {
        WebsiteInfoDto config = new WebsiteInfoDto();
        config.setConditionPath("/errno");
        config.setConditionValue("0");
        config.setInformationUrl("https://www.cls.cn/v3/depth/home/assembled/1000?app=CailianpressWeb&os=web&sv=8.7.9&sign=b02d8f7bc4c45eeb3e86904203597da2");
        config.setDataPath("/data/depth_list");
        config.setRequestType(HttpRequestTypeEnum.GET);
        config.setProxy(Boolean.FALSE);
        config.setFieldMapping("title||title,id||url,brief||summary");
        config.setFormatter("https://www.cls.cn/detail/${url}");
        config.setLogoUrl("https://cdnjs.cls.cn/www/20200601/image/logo.png");
        NewsListJsonParser parser = new NewsListJsonParser();
        ReflectUtil.setFieldValue(parser, "objectMapper", objectMapper);
        List<NewsVo> list = parser.parseNews(config);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
    }

    // TODO 证券时报网 快讯 https://stcn.com/article/list.html?type=kx
    @Test
    public void parse15() {
        WebsiteInfoDto config = new WebsiteInfoDto();
        config.setConditionPath("/state");
        config.setConditionValue("1");
        config.setInformationUrl("https://stcn.com/article/list.html?type=kx");
        config.setDataPath("/data");
        config.setRequestType(HttpRequestTypeEnum.GET);
        config.setProxy(Boolean.FALSE);
        config.setFieldMapping("title||title,share_url||url,content||summary");
        config.setFormatter("");
        config.setLogoUrl("https://static-web.stcn.com/static/images/stcn.png");
        config.setRequestHeaders("""
                Content-Type||application/json, text/javascript, */*; q=0.01
                ##referer||https://stcn.com/article/list/kx.html
                ##user-agent||Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36
                """);
        NewsListJsonParser parser = new NewsListJsonParser();
        ReflectUtil.setFieldValue(parser, "objectMapper", objectMapper);
        List<NewsVo> list = parser.parseNews(config);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
    }


    // oschina 博客
    @Test
    public void parse16() {
        WebsiteInfoDto config = new WebsiteInfoDto();
        config.setInformationUrl("https://apiv1.oschina.net/oschinapi/home/blogTime?pageNum=1&pageSize=30&tag=0");
        config.setRequestType(HttpRequestTypeEnum.GET);
        config.setProxy(Boolean.FALSE);
        config.setConditionPath("/code");
        config.setConditionValue("200");
        config.setDataPath("/result/list");
        config.setFieldMapping("objId||id,objTitle||title,userVo/spaceUrl||url,detail||summary");
        config.setFormatter("${url}/blog/${id}");
        NewsListJsonParser parser = new NewsListJsonParser();
        ReflectUtil.setFieldValue(parser, "objectMapper", objectMapper);
        List<NewsVo> list = parser.parseNews(config);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
        Assert.assertFalse(CharSequenceUtil.isAllBlank(list.getFirst().getTitle(), list.getFirst().getSummary()));
    }

    // oschina 博客
    @Test
    public void parse17() {
        WebsiteInfoDto config = new WebsiteInfoDto();
        config.setInformationUrl("https://apiv1.oschina.net/oschinapi/home/blogHot?pageNum=1&pageSize=30&tag=4");
        config.setRequestType(HttpRequestTypeEnum.GET);
        config.setProxy(Boolean.FALSE);
        config.setConditionPath("/code");
        config.setConditionValue("200");
        config.setDataPath("/result/list");
        config.setFieldMapping("objId||id,objTitle||title,userVo/spaceUrl||url,detail||summary");
        config.setFormatter("${url}/blog/${id}");
        NewsListJsonParser parser = new NewsListJsonParser();
        ReflectUtil.setFieldValue(parser, "objectMapper", objectMapper);
        List<NewsVo> list = parser.parseNews(config);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
        Assert.assertFalse(CharSequenceUtil.isAllBlank(list.getFirst().getTitle(), list.getFirst().getSummary()));
    }

    // 中国政府网 最新政策
    @Test
    public void parse18() {
        WebsiteInfoDto config = new WebsiteInfoDto();
        config.setInformationUrl("https://www.gov.cn/zhengce/zuixin/ZUIXINZHENGCE.json");
        config.setRequestType(HttpRequestTypeEnum.GET);
        config.setProxy(Boolean.FALSE);
        config.setDataPath("/");
        config.setFieldMapping("TITLE||title,URL||url,TITLE||summary");
        config.setFormatter("");
        NewsListJsonParser parser = new NewsListJsonParser();
        ReflectUtil.setFieldValue(parser, "objectMapper", objectMapper);
        List<NewsVo> list = parser.parseNews(config);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
        Assert.assertFalse(CharSequenceUtil.isAllBlank(list.getFirst().getTitle(), list.getFirst().getSummary()));
    }

    // 中国政府网 政策解读
    @Test
    public void parse19() {
        WebsiteInfoDto config = new WebsiteInfoDto();
        config.setInformationUrl("https://www.gov.cn/zhengce/jiedu/ZCJD_QZ.json");
        config.setRequestType(HttpRequestTypeEnum.GET);
        config.setProxy(Boolean.FALSE);
        config.setDataPath("/");
        config.setFieldMapping("TITLE||title,URL||url,TITLE||summary");
        config.setFormatter("");
        NewsListJsonParser parser = new NewsListJsonParser();
        ReflectUtil.setFieldValue(parser, "objectMapper", objectMapper);
        List<NewsVo> list = parser.parseNews(config);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
        Assert.assertFalse(CharSequenceUtil.isAllBlank(list.getFirst().getTitle(), list.getFirst().getSummary()));
    }
}
