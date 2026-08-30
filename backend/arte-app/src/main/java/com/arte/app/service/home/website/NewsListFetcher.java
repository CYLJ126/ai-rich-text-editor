package com.arte.app.service.home.website;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.text.CharSequenceUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.arte.app.common.constant.WebsiteNewsParamHandler;
import com.arte.app.pojo.home.website.WebsiteInfoDto;
import com.arte.core.enums.HttpRequestTypeEnum;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 获取网站新闻
 *
 * @author zhangsc
 * @since 2025/4/14 9:46
 */
@Slf4j
public class NewsListFetcher {

    private NewsListFetcher() {
    }

    /**
     * 构建 HttpRequest，从 WebsiteInfoDto 中统一组装请求头、代理、超时等信息
     *
     * @param websiteInfo 网站配置信息
     * @param proxyHost   代理主机
     * @param proxyPort   代理端口
     * @return 构建好的 HttpRequest
     */
    public static HttpRequest buildHttpRequest(WebsiteInfoDto websiteInfo, String proxyHost, int proxyPort, int timeout) {
        return buildHttpRequest(websiteInfo.getRequestType(), assembleUrl(websiteInfo), websiteInfo.getRequestHeaders(),
                websiteInfo.getRequestBody(), websiteInfo.getProxy(), proxyHost, proxyPort, timeout);
    }

    /**
     * 构建 HttpRequest，从 WebsiteInfoDto 中统一组装请求头、代理、超时等信息
     *
     * @param requestType 请求类型
     * @param url         请求地址
     * @param headers     请求头
     * @param requestBody 请求体
     * @param isProxy     是否需要代理
     * @param proxyHost   代理地址
     * @param proxyPort   代理端口
     * @param timeout     超时时间
     * @return HTTP 请求客户端
     */
    public static HttpRequest buildHttpRequest(HttpRequestTypeEnum requestType, String url, String headers,
                                               String requestBody, boolean isProxy, String proxyHost, int proxyPort, int timeout) {
        log.info("请求地址【{}】，请求类型【{}】", url, requestType.getValue());
        HttpRequest httpRequest = HttpRequestTypeEnum.isGet(requestType) ? HttpRequest.get(url) : HttpRequest.post(url);
        // 组装请求头
        if (CharSequenceUtil.isNotBlank(headers)) {
            httpRequest.headerMap(parseHeaders(headers), true);
        }
        // 设置跟随重定向
        httpRequest.setFollowRedirects(true);
        // 设置代理
        if (isProxy) {
            httpRequest.setHttpProxy(proxyHost, proxyPort);
        }
        // 设置超时（保底 10 秒）
        httpRequest.setConnectionTimeout(Math.max(timeout, 10000));

        // POST 请求组装 body
        if (HttpRequestTypeEnum.isPost(requestType)) {
            httpRequest.body(parseBody(requestBody));
        }

        // 设置信任证书
        if (StrUtil.startWith(url, "https")) {
            httpRequest.setSSLSocketFactory(getSSLSocketFactory());
        }
        return httpRequest;
    }

    /**
     * 获取新闻内容
     *
     * @param websiteInfo 网站配置信息
     * @param proxyHost   代理主机
     * @param proxyPort   代理端口
     * @return 响应内容
     */
    public static String fetchNews(WebsiteInfoDto websiteInfo, String proxyHost, int proxyPort, int timeout) {
        String informationUrl = websiteInfo.getInformationUrl();
        if (CharSequenceUtil.isBlank(informationUrl)) {
            return CharSequenceUtil.EMPTY;
        }

        String assembledUrl = assembleUrl(websiteInfo);
        if (CharSequenceUtil.isBlank(assembledUrl)) {
            return CharSequenceUtil.EMPTY;
        }

        HttpRequest httpRequest = buildHttpRequest(websiteInfo, proxyHost, proxyPort, timeout);
        try (HttpResponse response = httpRequest.execute()) {
            if (!response.isOk()) {
                log.error("获取新闻列表出错，响应不为 200，配置【{}】，响应状态码【{}】，响应内容【{}】", websiteInfo, response.getStatus(), response.body());
                return CharSequenceUtil.EMPTY;
            }
            return response.body();
        }
    }

    /**
     * 组装请求 url
     * 如 http://www.abc.com/${unix-milli}，则将占位符替换成具体值，转换后为 http://www.abc.com/1744706283738
     *
     * @param websiteInfo 配置
     * @return 处理后的 url
     */
    private static String assembleUrl(WebsiteInfoDto websiteInfo) {
        // 使用线程安全的StringBuilder处理URL构建
        StringBuilder result = new StringBuilder();
        Pattern pattern = Pattern.compile("\\$\\{(.+?)\\}");
        Matcher matcher = pattern.matcher(websiteInfo.getInformationUrl());
        try {
            while (matcher.find()) {
                matcher.appendReplacement(result, WebsiteNewsParamHandler.transfer(matcher.group(1)));
            }
            matcher.appendTail(result);
            return result.toString();
        } catch (Exception e) {
            log.error("解析对象值失败，配置【{}】，新闻对象【{}】", websiteInfo, e);
        }
        return CharSequenceUtil.EMPTY;
    }

    /**
     * 解析请求头
     * 形如 origin-domain||www.hui.com##referer||https://www.epubit.com/
     * 以 || 连接请求头的名称和值，以 ## 区分两个不同的请求头
     *
     * @param headerStr 配置的请求头字符串
     * @return Map 形式的请求头
     */
    public static Map<String, String> parseHeaders(String headerStr) {
        Map<String, String> headers = MapUtil.newHashMap();
        if (CharSequenceUtil.isBlank(headerStr)) {
            return headers;
        }
        String[] pairs = headerStr.split("##");
        for (String pair : pairs) {
            String[] kv = pair.split("\\|\\|", 2);
            if (kv.length == 2) {
                headers.put(kv[0].trim(), kv[1].trim());
            }
        }
        return headers;
    }

    /**
     * 返回一个信任所有证书的 SSLSocketFactory
     *
     * @return SSLSocketFactory
     */
    public static SSLSocketFactory getSSLSocketFactory() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }

                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        }

                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());
            return sslContext.getSocketFactory();
        } catch (Exception e) {
            log.error("设置请求信任所有证书失败，将直接请求", e);
        }
        return null;
    }

    private static String parseBody(String bodyStr) {
        return CharSequenceUtil.blankToDefault(bodyStr, "{}");
    }

}