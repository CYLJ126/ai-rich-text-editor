package com.arte.app.advisor;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.arte.ai.advisor.AbstractRetrievalAugmentationAdvisor;
import com.arte.ai.common.enums.KnowledgeBaseTypeEnum;
import com.arte.ai.pojo.chat.ChatRagRequestDto;
import com.arte.ai.pojo.chat.ChatRequestDto;
import com.arte.ai.tool.PromptUtil;
import com.arte.app.api.base.TagService;
import com.arte.app.api.richtext.ArticleService;
import com.arte.app.pojo.base.TagDto;
import com.arte.app.pojo.base.TagPo;
import com.arte.app.pojo.richtext.ArticleDto;
import com.arte.app.pojo.richtext.ChunkDocument;
import com.arte.app.pojo.richtext.param.ArticleParam;
import com.arte.core.es.EsSearchResponse;
import com.arte.core.interceptor.MybatisInterceptor;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 文章检索 Advisor。
 *
 * @author CYLJ126
 * @since 2026/7/10 16:24
 **/
@NullMarked
@Service
@Slf4j
public class ArticleRetrievalAugmentationAdvisor extends AbstractRetrievalAugmentationAdvisor {

    @Resource
    private ArticleService articleService;

    @Resource
    private TagService tagService;

    @Override
    protected KnowledgeBaseTypeEnum getKnowledgeBaseType() {
        return KnowledgeBaseTypeEnum.ARTICLE;
    }

    @Override
    protected String getRetrievalContextParamKey() {
        return "esChunkList";
    }

    @Override
    public int getOrder() {
        return 10;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        ChatRequestDto chatRequestDto = getChatRequestDto(chatClientRequest);
        if (!canUseRetrieval(chatClientRequest)) {
            return chatClientRequest;
        }

        ChatRagRequestDto chatRagRequest = chatRequestDto.getChatRagRequest();
        Set<Integer> articleIds = Set.of();
        if (Objects.nonNull(chatRagRequest)) {
            articleIds = chatRagRequest.getArticleIds();
        }

        String systemText;
        if (articleIds.size() == 1) {
            // 已明确选中单篇文章时，问题只作为模型输入，不再作为 ES chunk 检索词。
            systemText = getSystemTextWithArticleId(chatClientRequest, articleIds.iterator().next());
        } else {
            // 多篇文章时，问题作为 ES chunk 检索词，做 Rag 检索。
            systemText = getSystemTextWithRetrieval(chatClientRequest, articleIds);
        }
        if (StrUtil.isBlank(systemText)) {
            return chatClientRequest;
        }

        List<Message> messages = new ArrayList<>(chatClientRequest.prompt().getInstructions());
        messages.addFirst(SystemMessage.builder().text(systemText).build());
        // 不能用 chatClientRequest.prompt().mutate()，会报空指针异常，估计是框架 bug
        Prompt newPrompt = new Prompt(messages, chatClientRequest.prompt().getOptions());
        return ChatClientRequest.builder().prompt(newPrompt).context(chatClientRequest.context()).build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        ChatResponse.Builder chatResponseBuilder;
        if (chatClientResponse.chatResponse() == null) {
            chatResponseBuilder = ChatResponse.builder();
        } else {
            chatResponseBuilder = ChatResponse.builder().from(chatClientResponse.chatResponse());
        }
        Object ctx = chatClientResponse.context().get(getRetrievalContextParamKey());
        if (ctx != null) {
            chatResponseBuilder.metadata(getRetrievalContextParamKey(), ctx);
        }
        return ChatClientResponse.builder()
                .chatResponse(chatResponseBuilder.build())
                .context(chatClientResponse.context())
                .build();
    }

    /**
     * 单篇文章处理
     *
     * @param chatClientRequest 请求
     * @param articleId         文章 ID
     * @return 系统消息
     */
    private String getSystemTextWithArticleId(ChatClientRequest chatClientRequest, Integer articleId) {
        ChatRequestDto chatRequestDto = getChatRequestDto(chatClientRequest);
        ArticleDto article = articleService.getCompleteArticle(articleId);
        if (Objects.isNull(article) || StrUtil.isBlank(article.getContentText())) {
            return StrUtil.EMPTY;
        }
        putIntoRequest(false, chatClientRequest, getRetrievalContextParamKey(), Map.of("source", "mysql", "articleId", articleId));
        // 标签
        List<String> tags = new ArrayList<>();
        if (CollUtil.isNotEmpty(article.getTags())) {
            QueryWrapper<TagDto> queryWrapper = new QueryWrapper<>();
            queryWrapper.in(TagPo.COL_ID, article.getTags());
            MybatisInterceptor.ignore();
            List<TagDto> tagList = tagService.list(queryWrapper);
            tags.addAll(tagList.stream().map(TagDto::getName).toList());
        }
        return getPromptByGenerateType(chatRequestDto, article, tags);
    }

    /**
     * 多篇文章处理，根据检索结果生成系统消息
     *
     * @param chatClientRequest 请求
     * @param articleIds        文章 ID 列表
     * @return 系统消息
     */
    private String getSystemTextWithRetrieval(ChatClientRequest chatClientRequest, Set<Integer> articleIds) {
        ChatRequestDto chatRequestDto = getChatRequestDto(chatClientRequest);
        ArticleParam articleParam = new ArticleParam();
        if (CollUtil.isNotEmpty(articleIds)) {
            articleParam.setArticleIds(articleIds);
        }
        articleParam.setSearchBingoText(getFinalQuery(chatClientRequest));
        articleParam.setSemanticSearch(Boolean.TRUE);

        // 执行混合检索
        EsSearchResponse<ChunkDocument> chunkResponse = articleService.hybridSearch(articleParam);

        // 提取文档内容（假设 ChunkDocument 有 getContentWithBreadcrumb()）
        // TODO 检索质量优化、检索结果筛选
        List<EsSearchResponse.Hit<ChunkDocument>> hits = chunkResponse.hits();
        if (hits.size() > 10) {
            hits = hits.stream().limit(10).toList();
        } else if (hits.isEmpty()) {
            return StrUtil.EMPTY;
        }
        Map<String, Object> metaInfo = Map.of("source", "elasticsearch",
                "chunkIds", hits.stream().map(hit -> hit.source().getChunkId()).toList());
        putIntoRequest(false, chatClientRequest, "esChunkList", metaInfo);
        String contextContent = hits.stream()
                .map(hit -> hit.source().getContentWithBreadcrumb())
                .collect(Collectors.joining("\n---\n"));
        return PromptUtil.replaceRag(contextContent, chatRequestDto.getSystemPrompt());
    }

    /**
     * 根据生成类型获取提示词
     *
     * @param chatRequest 请求参数
     * @param article        文章
     * @param tags           标签列表
     * @return 提示词
     */
    private String getPromptByGenerateType(ChatRequestDto chatRequest, ArticleDto article, List<String> tags) {
        return switch (chatRequest.getGenerateType()) {
            case SUMMARY -> PromptUtil.replaceArticleSummary(article.getTitle(), tags, article.getContentText(),
                    chatRequest.getCharacterCountCeil(), chatRequest.getSystemPrompt());
            case POLISH ->
                    PromptUtil.replacePolish(chatRequest.getOriginalText(), article.getTitle(), tags, article.getContentText(),
                            chatRequest.getCharacterCountCeil(), chatRequest.getSystemPrompt());
            case CONTINUATION -> PromptUtil.replaceContinuation(chatRequest.getOriginalText(), article.getTitle(), tags,
                    chatRequest.getCharacterCountCeil(), chatRequest.getSystemPrompt());
            default -> PromptUtil.replaceRag(article.getContentText(), chatRequest.getSystemPrompt());
        };
    }
}
