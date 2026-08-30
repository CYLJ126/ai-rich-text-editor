package com.arte.app.advisor;

import com.arte.ai.advisor.AbstractAdvisor;
import com.arte.ai.common.enums.GenerateTypeEnum;
import com.arte.ai.common.enums.KnowledgeBaseTypeEnum;
import com.arte.ai.pojo.chat.ChatRagRequestDto;
import com.arte.ai.pojo.chat.ChatRequestDto;
import com.arte.app.api.richtext.ArticleService;
import com.arte.app.pojo.richtext.ArticleDto;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.prompt.Prompt;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class ArticleRetrievalAugmentationAdvisorTest {

    @Test
    public void shouldUseMysqlFullTextAndKeepQuestionAsModelInputForSingleArticle() throws Exception {
        String question = "页面输入的问题不能作为 ES 关键词";
        String articleContent = "第一段正文占比 100%。\n第二段完整正文。";
        ArticleService articleService = mock(ArticleService.class);
        when(articleService.getCompleteArticle(42)).thenReturn(article("标题", articleContent));
        ArticleRetrievalAugmentationAdvisor advisor = createAdvisor(articleService);

        ChatClientRequest original = createRequest(question, Set.of(42));
        ChatClientRequest result = advisor.before(original, null);

        verify(articleService).getCompleteArticle(42);
        verify(articleService, never()).hybridSearch(any());
        assertTrue(result.prompt().getSystemMessage().getText().contains(articleContent));
        assertEquals(question, result.prompt().getUserMessage().getText());
        assertEquals(
                Map.of("source", "mysql", "articleId", 42),
                result.context().get("esChunkList"));
    }

    @Test
    public void shouldNotFallbackToEsWhenSingleArticleHasNoContentText() throws Exception {
        ArticleService articleService = mock(ArticleService.class);
        when(articleService.getCompleteArticle(42)).thenReturn(article("标题", " "));
        ArticleRetrievalAugmentationAdvisor advisor = createAdvisor(articleService);
        ChatClientRequest original = createRequest("问题", Set.of(42));

        ChatClientRequest result = advisor.before(original, null);

        assertSame(original, result);
        verify(articleService, never()).hybridSearch(any());
    }

    private static ArticleRetrievalAugmentationAdvisor createAdvisor(ArticleService articleService)
            throws Exception {
        ArticleRetrievalAugmentationAdvisor advisor = new ArticleRetrievalAugmentationAdvisor();
        Field field = ArticleRetrievalAugmentationAdvisor.class.getDeclaredField("articleService");
        field.setAccessible(true);
        field.set(advisor, articleService);
        return advisor;
    }

    private static ChatClientRequest createRequest(String question, Set<Integer> articleIds) {
        ChatRagRequestDto ragRequest = new ChatRagRequestDto()
                .setKnowledgeBaseType(KnowledgeBaseTypeEnum.ARTICLE)
                .setArticleIds(articleIds);
        ChatRequestDto requestDto = new ChatRequestDto()
                .setChatRagRequest(ragRequest)
                .setGenerateType(GenerateTypeEnum.CHAT);
        Map<String, Object> context = new HashMap<>();
        context.put(AbstractAdvisor.REQUEST_DTO, requestDto);
        context.put(AbstractAdvisor.FINAL_QUERY, question);
        return ChatClientRequest.builder()
                .prompt(new Prompt(question))
                .context(context)
                .build();
    }

    private static ArticleDto article(String title, String content) {
        ArticleDto article = new ArticleDto();
        article.setTitle(title);
        article.setContentText(content);
        return article;
    }
}
