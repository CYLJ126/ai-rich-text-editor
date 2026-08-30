package com.arte.app.service.home.website;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.text.CharSequenceUtil;
import com.arte.app.pojo.home.website.NewsVo;
import com.arte.app.pojo.home.website.WebsiteInfoDto;
import com.arte.core.serialize.SerializerFactory;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import tools.jackson.databind.ObjectMapper;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.List;

public class NewsListHtmlParserTest {

    private final ObjectMapper objectMapper = SerializerFactory.buildJsonMapperWithoutTypeProperty();

    //绕过 SSL，只能在测试使用，不能上到生产
    @Before
    public void setUp() throws NoSuchAlgorithmException, KeyManagementException {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, new SecureRandom());
        HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
    }

    // 思否 推荐博客 https://segmentfault.com/blogs
    @Test
    public void parse0() {
        WebsiteInfoDto config = new WebsiteInfoDto();
        config.setInformationUrl("https://segmentfault.com/blogs");
        config.setDataPath("script#__NEXT_DATA__;props.pageProps.initialState.blogs.articles.rows");
        config.setFieldMapping("title||title,url||url");
        config.setFormatter("https://segmentfault.com/${url}");
        NewsListHtmlParser parser = new NewsListHtmlParser();
        List<NewsVo> list = parser.parseNews(config);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
    }

    // 思否 最新资讯 https://segmentfault.com/news
    @Test
    public void parse01() {
        WebsiteInfoDto config = new WebsiteInfoDto();
        config.setInformationUrl("https://segmentfault.com/news");
        config.setDataPath("script#__NEXT_DATA__;props.pageProps.initialState.news.articles.rows");
        config.setFieldMapping("data.title||title,data.url||url");
        config.setFormatter("https://segmentfault.com/${url}");
        NewsListHtmlParser parser = new NewsListHtmlParser();
        List<NewsVo> list = parser.parseNews(config);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
    }

    // 思否 最新博客 https://segmentfault.com/blogs/newest
    @Test
    public void parse02() {
        WebsiteInfoDto config = new WebsiteInfoDto();
        config.setInformationUrl("https://segmentfault.com/blogs/newest");
        config.setDataPath("script#__NEXT_DATA__;props.pageProps.initialState.blogs.articles.rows");
        config.setFieldMapping("title||title,url||url");
        config.setFormatter("https://segmentfault.com/${url}");
        NewsListHtmlParser parser = new NewsListHtmlParser();
        List<NewsVo> list = parser.parseNews(config);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
    }

    // 思否 极客观点 https://segmentfault.com/site/thinking
    @Test
    public void parse03() {
        WebsiteInfoDto config = new WebsiteInfoDto();
        config.setInformationUrl("https://segmentfault.com/site/thinking");
        config.setDataPath("script#__NEXT_DATA__;props.pageProps.initialState.sites.questionList.rows");
        config.setFieldMapping("title||title,url||url");
        config.setFormatter("https://segmentfault.com/${url}");
        NewsListHtmlParser parser = new NewsListHtmlParser();
        List<NewsVo> list = parser.parseNews(config);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
    }

    // 思否 周榜资讯 https://segmentfault.com/news/hottest/weekly
    @Test
    public void parse04() {
        WebsiteInfoDto config = new WebsiteInfoDto();
        config.setInformationUrl("https://segmentfault.com/news/hottest/weekly");
        config.setDataPath("script#__NEXT_DATA__;props.pageProps.initialState.news.articles.rows");
        config.setFieldMapping("data.title||title,data.url||url");
        config.setFormatter("https://segmentfault.com/${url}");
        NewsListHtmlParser parser = new NewsListHtmlParser();
        List<NewsVo> list = parser.parseNews(config);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
    }

    // oschina 资讯
    @Test
    public void osChina0() {
        WebsiteInfoDto config = new WebsiteInfoDto();
        config.setInformationUrl("https://www.oschina.net/news");
        config.setDataPath(".news-item");
        config.setFieldMapping(".news-item#data-url||url,.title#title||title,.line-clamp(text)||summary");
        config.setProxy(Boolean.FALSE);
        NewsListHtmlParser parser = new NewsListHtmlParser();
        List<NewsVo> list = parser.parseNews(config);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
        Assert.assertFalse(CharSequenceUtil.isAllBlank(list.get(0).getTitle(), list.get(0).getSummary()));
    }

    // 人人都是产品经理-推荐文章 完成
    @Test
    public void parse2() {
        WebsiteInfoDto config = new WebsiteInfoDto();
        config.setInformationUrl("https://www.woshipm.com/posts");
        config.setDataPath("article");
        config.setFieldMapping(".content>a#href||url,.content>a#title||title,.about(text)||summary");
        NewsListHtmlParser parser = new NewsListHtmlParser();
        List<NewsVo> list = parser.parseNews(config);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
    }

    // 中国政府网 头条 完成
    @Test
    public void parse4() {
        WebsiteInfoDto config = new WebsiteInfoDto();
        config.setInformationUrl("https://www.gov.cn/toutiao");
        config.setDataPath(".infolist a");
        config.setFieldMapping("a#href||url,a(text)||title");
        config.setFormatter("https://www.gov.cn/${url#subBefore3}");
        NewsListHtmlParser parser = new NewsListHtmlParser();
        List<NewsVo> list = parser.parseNews(config);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
    }

    // 中国支付清算协会 完成
    // 政策法规：https://www.pcac.org.cn/eportal/ui?pageId=595021
    // 监管动态：https://www.pcac.org.cn/eportal/ui?pageId=595043
    // 行业动态：https://www.pcac.org.cn/eportal/ui?pageId=595046
    // 行业研究：https://www.pcac.org.cn/eportal/ui?pageId=595052
    // 行业数据：https://www.pcac.org.cn/eportal/ui?pageId=595055
    // 自律规范：https://www.pcac.org.cn/eportal/ui?pageId=595027
    @Test
    public void parse6() {
        WebsiteInfoDto config = new WebsiteInfoDto();
        config.setInformationUrl("https://www.pcac.org.cn/eportal/ui?pageId=595021");
        config.setDataPath(".fglist-title a");
        config.setFieldMapping("a#href||url,a(text)||title");
        config.setFormatter("https://www.pcac.org.cn${url}");
        NewsListHtmlParser parser = new NewsListHtmlParser();
        List<NewsVo> list = parser.parseNews(config);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
    }

    // 安全内参 完成
    // 安全内参 首页：https://www.secrss.com/
    // 安全内参 政策法规：https://www.secrss.com/articles?tag=%E6%94%BF%E7%AD%96%E6%B3%95%E8%A7%84
    // 安全内参 威胁态势：https://www.secrss.com/articles?tag=%E5%A8%81%E8%83%81%E6%80%81%E5%8A%BF
    // 安全内参 技术前沿：https://www.secrss.com/articles?tag=%E6%8A%80%E6%9C%AF%E5%89%8D%E6%B2%BF
    // 安全内参 安全实践：https://www.secrss.com/articles?tag=%E5%AE%89%E5%85%A8%E5%AE%9E%E8%B7%B5
    // 安全内参 产业研究：https://www.secrss.com/articles?tag=%E4%BA%A7%E4%B8%9A%E7%A0%94%E7%A9%B6
    @Test
    public void parse7() {
        WebsiteInfoDto config = new WebsiteInfoDto();
        config.setInformationUrl("https://www.secrss.com");
        config.setDataPath(".article-list li");
        config.setFieldMapping("h2>a(text)||title,h2>a#href||url,p>a(text)||summary");
        config.setLogoUrl("https://www.secrss.com/logo_lg.png");
        NewsListHtmlParser parser = new NewsListHtmlParser();
        List<NewsVo> list = parser.parseNews(config);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
    }

    // oschina AI & 大数据 完成
    @Test
    public void parse8() {
        WebsiteInfoDto config = new WebsiteInfoDto();
        config.setInformationUrl("https://www.oschina.net/group/ai-bigdata");
        config.setDataPath(".entry-list-box .zone-list-item-content");
        config.setRequestHeaders("user-agent||Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36");
        config.setFieldMapping(".zone-item-title>a#href||url,a(text)||title,.article-item__content(text)||summary");
        NewsListHtmlParser parser = new NewsListHtmlParser();
        List<NewsVo> list = parser.parseNews(config);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
        Assert.assertFalse(CharSequenceUtil.isAllBlank(list.get(0).getTitle(), list.get(0).getSummary()));
    }

    // Java技术站 AI 完成
    @Test
    public void parse9() {
        WebsiteInfoDto config = new WebsiteInfoDto();
        config.setInformationUrl("https://www.javastack.cn/ai");
        config.setDataPath(".posts-wrapper article");
        config.setFieldMapping("a#href||url,a#title||title,.entry-excerpt(text)||summary");
        NewsListHtmlParser parser = new NewsListHtmlParser();
        List<NewsVo> list = parser.parseNews(config);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
        Assert.assertFalse(CharSequenceUtil.isAllBlank(list.get(0).getTitle(), list.get(0).getSummary()));
    }

    // 金管局 政策法规
    @Test
    public void parse10() {
        WebsiteInfoDto config = new WebsiteInfoDto();
        config.setInformationUrl("https://www.nfra.gov.cn/cn/static/data/DocInfo/SelectItemAndDocByItemPId/data_itemId=914,pageSize=10.json");
        config.setDataPath(".caidan-right-table a");
        config.setFieldMapping("a(text)||title,a#ng-href||url");
        config.setFormatter("https://www.nfra.gov.cn/cn/view/pages/");
        NewsListHtmlParser parser = new NewsListHtmlParser();
        List<NewsVo> list = parser.parseNews(config);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
    }

    // v2ex
    @Test
    public void parse11() {
        WebsiteInfoDto config = new WebsiteInfoDto();
        config.setInformationUrl("https://www.v2ex.com/?tab=tech");
        config.setDataPath(".item_title");
        config.setFieldMapping("a(text)||title,a#href||url");
        config.setFormatter("https://www.v2ex.com${url}");
        NewsListHtmlParser parser = new NewsListHtmlParser();
        List<NewsVo> list = parser.parseNews(config);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
    }

    /*// 人民银行 TODO
    @Test
    public void parse12() {
        WebsiteInfoDto config = new WebsiteInfoDto();
        config.setInformationUrl("http://www.pbc.gov.cn/goutongjiaoliu/113456/113469/index.html");
        config.setDataPath("table a");
        config.setFieldMapping("text||title,href||url");
        NewsListHtmlParser parser = new NewsListHtmlParser();
        List<NewsVo> list = parser.parseNews(config);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
    }*/

    // 中商情报网 首页 https://big5.askci.com/news/chanye/
    @Test
    public void parse13() {
        WebsiteInfoDto config = new WebsiteInfoDto();
        config.setInformationUrl("https://big5.askci.com/news/chanye/");
        config.setDataPath(".content_list_img_23");
        config.setFieldMapping("a#title||title,a#href||url");
        config.setFormatter("");
        config.setLogoUrl("https://image1.askci.com/ranking-list/images/logo-20200708.png");
        NewsListHtmlParser parser = new NewsListHtmlParser();
        List<NewsVo> list = parser.parseNews(config);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
    }

    // 中商情报网 热门排行榜 https://top.askci.com/
    @Test
    public void parse14() {
        WebsiteInfoDto config = new WebsiteInfoDto();
        config.setInformationUrl("https://top.askci.com/");
        config.setDataPath(".top-ranking-group-list");
        config.setFieldMapping("a#title||title,a#href||url");
        config.setFormatter("");
        config.setLogoUrl("https://image1.askci.com/ranking-list/images/logo-20200708.png");
        NewsListHtmlParser parser = new NewsListHtmlParser();
        List<NewsVo> list = parser.parseNews(config);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
    }

    // Reportify 新闻 https://reportify.cn/news
    @Test
    public void parse15() {
        WebsiteInfoDto config = new WebsiteInfoDto();
        config.setInformationUrl("https://reportify.cn/news/");
        config.setDataPath("div[class^=ReportListItem_listItem]");
        config.setFieldMapping("a>.line-clamp-1(text)||title,a#href||url,div[class^=ReportListItem_summary](text)||summary");
        config.setFormatter("https://reportify.cn/${url}");
        config.setLogoUrl("https://files.reportify.cn/static/media/logo_with_…?imageMogr2/quality/75/thumbnail/128x/format/webp");
        NewsListHtmlParser parser = new NewsListHtmlParser();
        List<NewsVo> list = parser.parseNews(config);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
    }

    // Reportify 热门 https://reportify.cn/hotspot
    @Test
    public void parse16() {
        WebsiteInfoDto config = new WebsiteInfoDto();
        config.setInformationUrl("https://reportify.cn/hotspot/");
        config.setDataPath("div[class^=ReportListItem_listItem]");
        config.setFieldMapping("a>.line-clamp-1(text)||title,a#href||url,div[class^=ReportListItem_summary](text)||summary");
        config.setFormatter("https://reportify.cn${url}");
        config.setLogoUrl("https://files.reportify.cn/static/media/logo_with_…?imageMogr2/quality/75/thumbnail/128x/format/webp");
        NewsListHtmlParser parser = new NewsListHtmlParser();
        List<NewsVo> list = parser.parseNews(config);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
    }

    // 经济参考报 要闻 https://www.jjckb.cn/yw.htm
    @Test
    public void parse17() {
        WebsiteInfoDto config = new WebsiteInfoDto();
        config.setInformationUrl("https://www.jjckb.cn/yw.htm");
        config.setDataPath(".tit");
        config.setFieldMapping("a(text)||title,a#href||url");
        config.setFormatter("https://www.jjckb.cn/${url}");
        config.setLogoUrl("https://www.jjckb.cn/images/2020weblogo.gif");
        NewsListHtmlParser parser = new NewsListHtmlParser();
        List<NewsVo> list = parser.parseNews(config);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
    }

    // 经济参考报 信息披露平台 https://www.jjckb.cn//xinpi/xinpipt.htm
    @Test
    public void parse18() {
        WebsiteInfoDto config = new WebsiteInfoDto();
        config.setInformationUrl("https://www.jjckb.cn//xinpi/xinpipt.htm");
        config.setDataPath(".py-1.clearfix");
        config.setFieldMapping("a(text)||title,a#href||url");
        config.setFormatter("https://www.jjckb.cn/${url}");
        config.setLogoUrl("https://www.jjckb.cn/images/2020weblogo.gif");
        NewsListHtmlParser parser = new NewsListHtmlParser();
        List<NewsVo> list = parser.parseNews(config);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
    }

    // 同花顺 头条 https://www.10jqka.com.cn/
    @Test
    public void parse19() {
        WebsiteInfoDto config = new WebsiteInfoDto();
        config.setInformationUrl("https://www.10jqka.com.cn/");
        config.setDataPath(".block.transition-opacity");
        config.setFieldMapping("h3或h4(text)||title,a#href||url,p(text)||summary");
        config.setFormatter("https://www.10jqka.com.cn/${url}");
        config.setLogoUrl("https://s.thsi.cn/cd/news-p-fe-app-news-flow-home/home/_next/static/media/logo.1c8fc73f.png");
        NewsListHtmlParser parser = new NewsListHtmlParser();
        List<NewsVo> list = parser.parseNews(config);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
    }

    // 证券时报网 要闻 https://stcn.com/article/list/yw.html
    @Test
    public void parse20() {
        WebsiteInfoDto config = new WebsiteInfoDto();
        config.setInformationUrl("https://stcn.com/article/list/yw.html");
        config.setDataPath("li>.content");
        config.setFieldMapping(".tt>a(text)||title,.tt>a#href||url,.text>a(text)||summary");
        config.setFormatter("https://stcn.com/${url}");
        config.setLogoUrl("https://static-web.stcn.com/static/images/stcn.png");
        NewsListHtmlParser parser = new NewsListHtmlParser();
        List<NewsVo> list = parser.parseNews(config);
        Assert.assertTrue(CollUtil.isNotEmpty(list));
    }
}