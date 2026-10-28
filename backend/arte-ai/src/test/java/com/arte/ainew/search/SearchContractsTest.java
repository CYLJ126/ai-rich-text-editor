package com.arte.ainew.search;

import com.arte.ainew.application.conversation.NoopConversationArchiveReader;
import com.arte.ainew.application.conversation.UnsupportedConversationArchiveCommands;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.search.SearchCursor;
import com.arte.ainew.common.search.SearchFailure;
import com.arte.ainew.common.search.SearchPreview;
import com.arte.ainew.common.search.SearchText;
import com.arte.ainew.config.NewAiSearchProperties;
import com.arte.ainew.infrastructure.search.ElasticsearchConversationSearchIndex;
import com.arte.ainew.infrastructure.search.SearchIndexDsl;
import com.arte.ainew.pojo.search.ConversationSearch;
import com.arte.ainew.pojo.search.SearchIndexRecord;
import com.arte.ainew.spi.search.ConversationSearchIndex;
import com.arte.ainew.web.request.ConversationSearchRequests;
import org.junit.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.*;

import static org.junit.Assert.*;

public class SearchContractsTest {
    private static final ExecutionOwner OWNER = new ExecutionOwner("tenant", "workspace", "subject");

    @Test
    public void invisibleQueriesFailButJoinedEmojiAndMixedTextRemainIntact() {
        for (var query : List.of(" ", "\n\t", "\u00a0", "\u200b\ufeff", "\u200d\ufe0f"))
            assertThrows(IllegalArgumentException.class, () -> SearchText.query(query));
        assertEquals("👩‍💻", SearchText.query(" 👩‍💻 "));
        assertEquals(1, SearchText.visibleLength("👩‍💻"));
        assertEquals("中文 Redis", SearchText.query(" 中文 Redis "));
        assertEquals("中文", SearchText.query("\u00a0中文\u00a0"));
        assertThrows(IllegalArgumentException.class, () -> SearchText.query("字".repeat(129)));
    }

    @Test
    public void emptyGroupsAreEquivalentAndPublicNonEmptyGroupsFailExplicitly() {
        var absent = new ConversationSearch.Query("会话", null, 20, null);
        assertEquals(absent, new ConversationSearch.Query("会话", List.of(), 20, null));
        assertThrows(IllegalArgumentException.class, () -> SearchText.groups(List.of(" ")));
        assertThrows(IllegalArgumentException.class, () -> SearchText.groups(List.of("\u00a0")));
        var failure = assertThrows(SearchFailure.class, () -> new ConversationSearch.Query("会话", List.of("group"), 20, null));
        assertEquals(SearchFailure.Code.GROUP_FILTER_NOT_AVAILABLE, failure.code());
    }

    @Test
    public void stableKeysSeparateOwnersKindsAndCandidatesWithoutDelimiterCollisions() {
        var user = SearchIndexRecord.message(OWNER, "conv", "turn", "message", null, 0, 1, "问题", null, false, 1);
        var a = SearchIndexRecord.message(OWNER, "conv", "turn", "answer", "one", 1, 1, "回答", null, false, 1);
        var b = SearchIndexRecord.message(OWNER, "conv", "turn", "answer", "two", 2, 1, "回答", null, false, 1);
        assertNotEquals(a.entryKey(), b.entryKey());
        assertNotEquals(user.entryKey(), SearchIndexRecord.title(OWNER, "conv", "标题", null, 1).entryKey());
        assertNotEquals(user.entryKey(), SearchIndexRecord.message(new ExecutionOwner("tenant", "workspace", "other"),
                "conv", "turn", "message", null, 0, 1, "问题", null, false, 1).entryKey());
        assertNotEquals(SearchIndexRecord.key(OWNER, "a:b", "c"), SearchIndexRecord.key(OWNER, "a", "b:c"));
        assertEquals(List.of(), user.source().get("groupIds"));
        assertThrows(UnsupportedOperationException.class, () -> a.groupIds().add("x"));
    }

    @Test
    public void titleResultsCannotInventMessageLocationsAndHistoryModesAreExclusive() {
        String key = SearchIndexRecord.key(OWNER, "conv", "TITLE");
        var snippet = new ConversationSearch.Snippet("标题", List.of());
        new ConversationSearch.Item(key, "conv", "标题", ConversationSearch.Kind.TITLE, snippet, false, null, null, null, null, false);
        assertThrows(IllegalArgumentException.class, () -> new ConversationSearch.Item(key, "conv", "标题", ConversationSearch.Kind.TITLE,
                snippet, true, "turn", "message", null, null, false));
        new ConversationSearch.AroundQuery("conv", "turn", 1L, 10, null);
        new ConversationSearch.AroundQuery("conv", null, null, 10, "opaque");
        assertThrows(IllegalArgumentException.class, () -> new ConversationSearch.AroundQuery("conv", "turn", 1L, 10, "opaque"));
        assertThrows(IllegalArgumentException.class, () -> new ConversationSearch.AroundQuery("conv", "turn", 1L, 11, null));
    }

    @Test
    public void previewDecodesOnceAndUserMarkupCannotBecomeHighlightMarkers() {
        var snippet = SearchPreview.fromEncodedHighlight("&lt;script&gt;👩‍💻<arte-hit>中文 &amp; Redis</arte-hit>&lt;/script&gt;", 800);
        assertEquals("<script>👩‍💻中文 & Redis</script>", snippet.text());
        var range = snippet.highlights().getFirst();
        assertEquals("中文 & Redis", snippet.text().substring(range.start(), range.end()));
        var literal = SearchPreview.fromEncodedHighlight("&lt;arte-hit&gt;原文&lt;/arte-hit&gt;", 800);
        assertTrue(literal.highlights().isEmpty());
        assertEquals("<arte-hit>原文</arte-hit>", literal.text());
        assertEquals("👩‍💻", SearchPreview.fromEncodedHighlight("<arte-hit>👩‍💻中文</arte-hit>", 1).text());
        assertTrue(SearchPreview.fromEncodedHighlight("<arte-hit>无闭合", 800).highlights().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new ConversationSearch.Snippet("😀", List.of(new ConversationSearch.Highlight(0, 1))));
    }

    @Test
    public void incompleteEsResponsesFailAndDoNotBecomeBudgetPages() {
        for (int mode = 0; mode < 3; mode++) {
            int value = mode;
            var failure = assertThrows(SearchFailure.class, () -> ElasticsearchConversationSearchIndex.requireComplete(value == 0, value == 1 ? 1 : 0, value == 2));
            assertEquals(SearchFailure.Code.INCOMPLETE_RESPONSE, failure.code());
        }
        assertThrows(IllegalArgumentException.class, () -> new ConversationSearch.Page("session", List.of(), null, "next", "first",
                ConversationSearch.EndReason.END, true, null));
    }

    @Test
    public void rawDslAlwaysFiltersOwnerAndOmitsEmptyTermsAndFullMessageSource() {
        var props = NewAiSearchProperties.defaults();
        var read = new ConversationSearchIndex.Read(OWNER, "中文 Redis", List.of(), new ConversationSearchIndex.Pit("pit", "generation"),
                ConversationSearch.Direction.FIRST, List.of(), 20, Duration.ofMinutes(10));
        String json = JsonMapper.builder().build().writeValueAsString(SearchIndexDsl.search(read, props));
        assertTrue(json.contains("tenantId"));
        assertTrue(json.contains("workspaceId"));
        assertTrue(json.contains("subjectId"));
        assertFalse(json.contains("\"terms\""));
        assertFalse(json.contains("collapse"));
        assertFalse(json.contains("search_after"));
        assertTrue(json.contains("\"encoder\":\"html\""));
        assertTrue(json.contains("\"operator\":\"and\""));
        var source = (List<?>) SearchIndexDsl.search(read, props).get("_source");
        assertFalse(source.contains("body"));
        var grouped = new ConversationSearchIndex.Read(OWNER, "中文", List.of("b", "a", "b"), read.pit(), read.direction(), List.of(), 20, read.keepAlive());
        assertEquals(List.of("a", "b"), grouped.groupIds());
        assertTrue(JsonMapper.builder().build().writeValueAsString(SearchIndexDsl.search(grouped, props)).contains("\"terms\""));
    }

    @Test
    public void defaultArchivePortsHaveNoSideEffectsAndNoFakeSuccess() {
        var reader = new NoopConversationArchiveReader();
        var result = reader.findArchivedConversationIds(OWNER, Set.of("conv")).block();
        assertEquals(Set.of(), result);
        assertThrows(UnsupportedOperationException.class, () -> result.add("conv"));
        var commands = new UnsupportedConversationArchiveCommands();
        assertFalse(commands.isSupported());
        assertEquals(SearchFailure.Code.ARCHIVE_NOT_AVAILABLE, assertThrows(SearchFailure.class, () -> commands.archive("conv", null).block()).code());
        assertEquals(SearchFailure.Code.ARCHIVE_NOT_AVAILABLE, assertThrows(SearchFailure.class, () -> commands.unarchive("conv", null).block()).code());
    }

    @Test
    public void typedClientAcceptsDslAndPreservesCompleteSortPrecision() {
        var read = new ConversationSearchIndex.Read(OWNER, "会话", List.of(), new ConversationSearchIndex.Pit("pit", "generation"),
                ConversationSearch.Direction.BACKWARD, Arrays.asList(1.0, null, "a".repeat(64), 9007199254740993L), 20, Duration.ofMinutes(10));
        String json = JsonMapper.builder().build().writeValueAsString(SearchIndexDsl.search(read, NewAiSearchProperties.defaults()));
        var request = co.elastic.clients.elasticsearch.core.SearchRequest.of(b -> b.withJson(new java.io.StringReader(json)));
        assertEquals(4, request.sort().size());
        assertEquals(4, request.searchAfter().size());
        assertEquals(9007199254740993L, request.searchAfter().getLast().longValue());
        assertTrue(request.searchAfter().get(1).isNull());
        assertThrows(IllegalArgumentException.class, () -> new ConversationSearchIndex.Read(OWNER, "会话", List.of(), read.pit(),
                read.direction(), Arrays.asList(Double.NaN, null, "a".repeat(64), 1L), 20, Duration.ofMinutes(10)));
    }

    @Test
    public void nullableSortBoundariesAreDefensivelyCopiedAcrossHitRequestAndCursor() {
        var decimal = new java.math.BigDecimal("0.12345678901234567890123456789");
        var integer = new java.math.BigInteger("9007199254740993");
        String entry = "a".repeat(64);
        var input = new ArrayList<Object>(Arrays.asList(decimal, null, entry, integer));
        var expected = Arrays.<Object>asList(decimal, null, entry, integer);
        var read = new ConversationSearchIndex.Read(OWNER, "会话", List.of(), new ConversationSearchIndex.Pit("pit", "generation"),
                ConversationSearch.Direction.FORWARD, input, 20, Duration.ofMinutes(10));
        var hit = new ConversationSearchIndex.Hit(entry, Map.of(), input, Map.of());
        var cursor = new SearchCursor.Page(java.util.UUID.randomUUID().toString(), "v1", read.direction(), input, true);
        input.clear();
        assertEquals(expected, read.after());
        assertEquals(expected, hit.sort());
        assertEquals(expected, cursor.after());
        assertSame(decimal, read.after().getFirst());
        assertSame(integer, hit.sort().getLast());
        assertThrows(UnsupportedOperationException.class, () -> read.after().set(1, "changed"));
        assertThrows(UnsupportedOperationException.class, () -> hit.sort().clear());
        assertThrows(UnsupportedOperationException.class, () -> cursor.after().add("changed"));
        String key = SearchCursor.randomKey();
        var decoded = SearchCursor.verify(SearchCursor.sign(cursor, key), key, SearchCursor.Page.class);
        assertEquals(expected, decoded.after());
        assertThrows(UnsupportedOperationException.class, () -> decoded.after().clear());
    }

    @Test
    public void recallRuleKeepsAllStopwordQueriesEmptyAndRequiresContiguousLiteralPhrase() {
        var read = new ConversationSearchIndex.Read(OWNER, "返回 空值", List.of(), new ConversationSearchIndex.Pit("pit", "generation"),
                ConversationSearch.Direction.FIRST, List.of(), 20, Duration.ofMinutes(10));
        var json = JsonMapper.builder().build();
        String dsl = json.writeValueAsString(SearchIndexDsl.search(read, NewAiSearchProperties.defaults()));
        assertTrue(dsl.contains("body.recall"));
        assertTrue(dsl.contains("body.literal"));
        assertTrue(dsl.contains("\"slop\":0"));
        assertTrue(dsl.contains("\"require_field_match\":true"));
        assertEquals(Map.of("match_none", Map.of()), SearchIndexDsl.search(read, NewAiSearchProperties.defaults(), false).get("query"));
    }

    @Test
    public void httpDtosAndPublicPagesDoNotAcceptOrSerializePitOrOwner() {
        var mapper = JsonMapper.builder().build();
        var query = new ConversationSearchRequests.Search(null, "中文", null, 20, null).toQuery();
        assertEquals(List.of(), query.groupIds());
        var page = new ConversationSearch.Page("session", List.of(), null, null, "first", ConversationSearch.EndReason.END, false, null);
        String json = mapper.writeValueAsString(page);
        assertFalse(json.contains("owner"));
        assertFalse(json.contains("pit"));
        assertFalse(json.contains("subjectId"));
    }
}
