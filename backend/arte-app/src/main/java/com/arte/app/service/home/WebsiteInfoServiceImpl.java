package com.arte.app.service.home;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.ConcurrentHashSet;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.arte.app.api.home.WebsiteInfoService;
import com.arte.app.common.constant.WebsiteNewsParamHandler;
import com.arte.app.common.enums.WebsiteResolveTypeEnum;
import com.arte.app.mapper.home.WebsiteInfoMapper;
import com.arte.app.pojo.home.website.NewsVo;
import com.arte.app.pojo.home.website.WebsiteInfoDto;
import com.arte.app.pojo.home.website.WebsiteInfoPo;
import com.arte.app.service.home.website.*;
import com.arte.core.enums.HttpRequestTypeEnum;
import com.arte.core.enums.StatusEnum;
import com.arte.core.exception.BusinessException;
import com.arte.core.utils.LogUtil;
import com.arte.core.utils.crypto.Sm3Util;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * <p>
 * 资讯网站信息 服务实现类
 * </p>
 *
 * @author zhangsc
 * @since 2025-04-12
 */
@Slf4j
@Service
public class WebsiteInfoServiceImpl extends ServiceImpl<WebsiteInfoMapper, WebsiteInfoDto> implements WebsiteInfoService {

    @Value("${http.proxy.host}")
    private String proxyHost;

    @Value("${http.proxy.port}")
    private int proxyPort;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private ApplicationContext applicationContext;

    private final WeakHashMap<String, byte[]> logoMap = new WeakHashMap<>();

    private final AtomicReference<byte[]> defaultLogoReference = new AtomicReference<>();

    private final Map<WebsiteResolveTypeEnum, NewsListParser> parserMap = new HashMap<>();

    @Override
    public List<WebsiteInfoDto> listByType(WebsiteInfoDto param) {
        RBucket<Set<WebsiteInfoDto>> bucket = redissonClient.getBucket(getWebsiteTypeKey(param.getType()));
        List<WebsiteInfoDto> result = new ArrayList<>();
        if (bucket.isExists()) {
            bucket.get().forEach(websiteInfo -> {
                RBucket<List<NewsVo>> newsListBucket = redissonClient.getBucket(getNewsListKey(websiteInfo));
                if (newsListBucket.isExists()) {
                    websiteInfo.setNewsList(newsListBucket.get());
                    result.add(websiteInfo);
                }
            });
        }
        result.sort(Comparator.comparing(WebsiteInfoPo::getWebsiteOrder));
        return result;
    }

    @Scheduled(cron = "0 0 0/8 * * ?")
    public void load() {
        CompletableFuture.runAsync(() -> {
            LogUtil.setIdIfNull();
            this.refreshNews();
        });
    }

    @Override
    public Boolean refreshNews() {
        Duration duration = Duration.of(9, ChronoUnit.HOURS);
        QueryWrapper<WebsiteInfoDto> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(WebsiteInfoPo.COL_STATUS, StatusEnum.DOING);
        List<WebsiteInfoDto> websiteInfos = baseMapper.selectList(queryWrapper);
        // 新闻类型分组
        Map<String, Set<WebsiteInfoDto>> newsTabMap = new ConcurrentHashMap<>();
        // 已加载的新闻网站 ID
        Set<Integer> websiteIdsOfNoNews = new ConcurrentHashSet<>();
        logoMap.clear();

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        websiteInfos.forEach(websiteInfo -> {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                initialLogoImg(websiteInfo);
                NewsListParser parser = getParser(websiteInfo.getResolveType());
                List<NewsVo> newsList = parser.parseNews(websiteInfo);
                if (CollUtil.isNotEmpty(newsList)) {
                    String websiteKey = getNewsListKey(websiteInfo);
                    newsTabMap.computeIfAbsent(websiteInfo.getType(), a -> new HashSet<>()).add(websiteInfo);
                    RBucket<List<NewsVo>> bucket = redissonClient.getBucket(websiteKey);
                    bucket.set(newsList, duration);
                } else {
                    websiteIdsOfNoNews.add(websiteInfo.getId());
                }
            });
            futures.add(future);
        });
        // 等待所有任务完成或超时 15 秒
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    log.warn("部分网站新闻加载超时", ex);
                    return null;
                }).join();

        newsTabMap.forEach((key, value) -> {
            RBucket<Set<WebsiteInfoDto>> bucket = redissonClient.getBucket(getWebsiteTypeKey(key));
            bucket.set(value, duration);
        });
        log.info("本次加载新闻版本【{}】个，加载新闻网站【{}】个，未加载的网站 ID 为 ({})", newsTabMap.size(), websiteInfos.size() - websiteIdsOfNoNews.size(),
                websiteIdsOfNoNews.stream().sorted(Comparator.naturalOrder()).map(String::valueOf).collect(Collectors.joining(",")));
        return Boolean.TRUE;
    }

    @Override
    public byte[] getLogoImg(WebsiteInfoDto param) {
        String logoUrl = param.getLogoUrl();
        String logoKey = Sm3Util.digest(logoUrl);
        if (logoMap.containsKey(logoKey)) {
            return logoMap.get(logoKey);
        }
        RBucket<byte[]> bucket = redissonClient.getBucket(getWebsiteLogoKey(logoKey), ByteArrayCodec.INSTANCE);
        if (bucket.isExists()) {
            return bucket.get();
        }
        QueryWrapper<WebsiteInfoDto> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(WebsiteInfoPo.COL_ID, param.getId());
        WebsiteInfoDto websiteInfo = getOne(queryWrapper);
        if (websiteInfo == null) {
            return new byte[0];
        }
        if (initialLogoImg(websiteInfo)) {
            return logoMap.get(logoKey);
        } else {
            param.setLogoUrl("");
            return defaultLogoReference.get();
        }
    }

    private boolean initialLogoImg(WebsiteInfoDto websiteInfo) {
        String logoUrl = websiteInfo.getLogoUrl();
        String logoKey = Sm3Util.digest(logoUrl);
        if (logoMap.containsKey(logoKey)) {
            return true;
        }

        HttpRequest httpRequest = NewsListFetcher.buildHttpRequest(HttpRequestTypeEnum.GET, logoUrl, websiteInfo.getLogoRequestHeaders(), StrUtil.EMPTY, websiteInfo.getProxy(), proxyHost, proxyPort, 10000);
        byte[] imageBytes;
        try (HttpResponse response = httpRequest.execute()) {
            if (response.isOk()) {
                imageBytes = response.bodyBytes();
                logoMap.put(logoKey, imageBytes);
                RBucket<byte[]> bucket = redissonClient.getBucket(getWebsiteLogoKey(logoKey), ByteArrayCodec.INSTANCE);
                bucket.set(imageBytes);
                return true;
            } else {
                log.info("ID 【{}】获取网站 logo 失败，状态码【{}】，加载默认 logo", websiteInfo.getId(), response.getStatus());
                // 加载 static/WebsiteLogo.png 到 defaultLogoReference
                loadDefaultLogo();
            }
        } catch (Exception e) {
            log.error("ID 【{}】获取网站 logo 失败", websiteInfo.getId(), e);
        }
        return false;
    }

    @PostConstruct
    private void init() {
        parserMap.put(WebsiteResolveTypeEnum.HTML, applicationContext.getBean(NewsListHtmlParser.class));
        parserMap.put(WebsiteResolveTypeEnum.JSON, applicationContext.getBean(NewsListJsonParser.class));
        parserMap.put(WebsiteResolveTypeEnum.STRING, applicationContext.getBean(NewsListStringParser.class));
        parserMap.put(WebsiteResolveTypeEnum.ESCAPE_STR, applicationContext.getBean(NewsListEscapeStrParser.class));
//        load();
    }

    private NewsListParser getParser(WebsiteResolveTypeEnum resolveType) {
        if (parserMap.containsKey(resolveType)) {
            return parserMap.get(resolveType);
        }
        throw new BusinessException("解析器配置错误");
    }

    private static String getWebsiteTypeKey(String key) {
        return WebsiteNewsParamHandler.WEBSITE_TYPE_PREFIX + key;
    }

    private static String getNewsListKey(WebsiteInfoDto websiteInfo) {
        return WebsiteNewsParamHandler.WEBSITE_NEWS_PREFIX + websiteInfo.getName() + "-" + websiteInfo.getModule();
    }

    private static String getWebsiteLogoKey(String logoKey) {
        return WebsiteNewsParamHandler.WEBSITE_LOGO_PREFIX + logoKey;
    }

    private void loadDefaultLogo() {
        if (defaultLogoReference.get() == null) {
            synchronized (this) {
                if (defaultLogoReference.get() == null) {
                    try {
                        // 从静态资源中读取默认logo
                        ClassLoader classLoader = getClass().getClassLoader();
                        try (var inputStream = classLoader.getResourceAsStream("static/WebsiteLogo.png")) {
                            if (inputStream != null) {
                                byte[] bytes = inputStream.readAllBytes();
                                defaultLogoReference.set(bytes);
                            }
                        }
                    } catch (Exception e) {
                        log.error("加载默认 logo 失败", e);
                    }
                }
            }
        }
    }
}
