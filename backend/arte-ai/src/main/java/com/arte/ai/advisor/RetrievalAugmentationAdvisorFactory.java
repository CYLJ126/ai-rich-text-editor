package com.arte.ai.advisor;

import com.arte.ai.common.enums.KnowledgeBaseTypeEnum;
import com.arte.ai.common.exception.RetrievalAugmentationException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 检索增强 Advisor 工厂类
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/7/10 16:51 ✾
 **/
@Service
public class RetrievalAugmentationAdvisorFactory {

    @Resource
    private ApplicationContext applicationContext;

    private final Map<KnowledgeBaseTypeEnum, Advisor> advisors = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        Advisor articleAdvisor = (Advisor) applicationContext.getBean(KnowledgeBaseTypeEnum.ARTICLE.getBeanName());
        advisors.put(KnowledgeBaseTypeEnum.ARTICLE, articleAdvisor);
    }

    public Advisor getAdvisor(KnowledgeBaseTypeEnum type) {
        if (advisors.containsKey(type)) {
            return advisors.get(type);
        }
        throw new RetrievalAugmentationException(type);
    }

}
