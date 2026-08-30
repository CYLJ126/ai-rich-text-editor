package com.nip.app.service.home.website;

import com.nip.app.pojo.home.website.NewsVo;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2025/4/15 21:54 ✾
 */
public class ChineseGovermentParser {

    public static List<NewsVo> fetchRecommendedNews() throws Exception {
        List<NewsVo> newsList = new ArrayList<>();
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
        // 获取网页内容
        Document doc = Jsoup.connect("https://www.gov.cn/toutiao/")
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .timeout(5000).get();
        // 选择推荐新闻区域
        Element recommendedSection = doc.selectFirst(".zsj_news");
        if (recommendedSection != null) {
            // 获取所有文章
            Elements articles = recommendedSection.select("li");

            for (Element article : articles) {
                // 获取内容区域
                // 获取链接
                Element link = article.selectFirst("a");
                if (link != null) {
                    NewsVo news = new NewsVo();
                    news.setTitle(link.attr("title"));
                    news.setUrl(link.attr("href"));
                    news.setSummary(link.text());
                    // 可以在这里添加其他字段的解析
                    newsList.add(news);
                }
            }
        }

        return newsList;
    }

    public static void main(String[] args) throws Exception {
        List<NewsVo> list = fetchRecommendedNews();
        System.out.println(list);
    }
}
