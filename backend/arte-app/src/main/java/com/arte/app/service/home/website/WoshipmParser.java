package com.arte.app.service.home.website;

import com.arte.app.pojo.home.website.NewsVo;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2025/4/15 21:54 ✾
 */
public class WoshipmParser {

    public static List<NewsVo> fetchRecommendedNews() throws IOException {
        List<NewsVo> newsList = new ArrayList<>();

        // 获取网页内容
        Document doc = Jsoup.connect("https://www.woshipm.com/").get();

        // 选择推荐新闻区域
        Element recommendedSection = doc.selectFirst(".js-postlist");
        if (recommendedSection != null) {
            // 获取所有文章
            Elements articles = recommendedSection.select("article");

            for (Element article : articles) {
                // 获取内容区域
                Element contentDiv = article.selectFirst(".content");
                if (contentDiv != null) {
                    // 获取链接
                    Element link = contentDiv.selectFirst("a");
                    if (link != null) {
                        NewsVo news = new NewsVo();
                        news.setTitle(link.attr("title"));
                        news.setUrl(link.attr("href"));
                        // 可以在这里添加其他字段的解析
                        newsList.add(news);
                    }
                }
            }
        }

        return newsList;
    }

    public static void main(String[] args) throws IOException {
        List<NewsVo> list = fetchRecommendedNews();
        System.out.println(list);
    }
}
