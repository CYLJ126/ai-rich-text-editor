package com.arte.app.service.richtext;

import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.text.CharSequenceUtil;
import com.arte.app.common.enums.richtext.TiptapNodeTypeEnum;
import com.arte.app.pojo.richtext.ArticleDocument;
import com.arte.app.pojo.richtext.ChunkDocument;
import com.arte.app.strategy.richtext.handler.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TiptapJsonParserTest {
    private TiptapJsonParser parser;

    @BeforeEach
    void setUp() {
        var strategies = List.of(
                new HeadingNodeHandler(),
                new ParagraphNodeHandler(),
                new CodeBlockNodeHandler(),
                new TableNodeHandler(),
                new HorizontalRuleNodeHandler(),
                new MediaNodeHandler(),
                new MermaidNodeHandler(),
                new MathNodeHandler(),
                new ListNodeHandler()
        );
        parser = new TiptapJsonParser(strategies, null);
    }

    /**
     * 测试解析标题和段落
     */
    @Test
    void should_parse_heading_and_paragraph() {
        String json = """
                {
                  "type": "doc",
                  "content": [
                    {
                      "type": "heading",
                      "attrs": {"id": "h1", "level": 2},
                      "content": [{"type": "text", "text": "概述"}]
                    },
                    {
                      "type": "paragraph",
                      "attrs": {"id": "p1"},
                      "content": [
                        {"type": "text", "text": "这是一段", "marks": [{"type": "bold"}]},
                        {"type": "text", "text": "普通文本"}
                      ]
                    }
                  ]
                }
                """;
        List<ChunkDocument> chunks = parser.parse(json, 1, buildMeta());
        assertEquals(2, chunks.size());
        ChunkDocument heading = chunks.getFirst();
        assertEquals(TiptapNodeTypeEnum.HEADING, heading.getChunkType());
        assertEquals("概述", heading.getContent());
        assertTrue(CharSequenceUtil.contains(heading.getBreadcrumb(), "概述"));
        ChunkDocument para = chunks.getLast();
        assertEquals(TiptapNodeTypeEnum.PARAGRAPH, para.getChunkType());
        assertTrue(CharSequenceUtil.contains(para.getContent(), "这是一段"));
        assertTrue(CharSequenceUtil.contains(para.getBoldTerms(), "这是一段"));
        assertEquals("概述", para.getSectionHeading());
    }

    /**
     * 测试解析顺序
     */
    @Test
    void should_link_prev_next_chunk_ids() {
        String json = """
                {
                  "type": "doc",
                  "content": [
                    {"type": "heading", "attrs": {"id": "h1", "level": 1},
                     "content": [{"type": "text", "text": "标题"}]},
                    {"type": "paragraph", "attrs": {"id": "p1"},
                     "content": [{"type": "text", "text": "段落1"}]},
                    {"type": "paragraph", "attrs": {"id": "p2"},
                     "content": [{"type": "text", "text": "段落2"}]}
                  ]
                }
                """;
        List<ChunkDocument> chunks = parser.parse(json, 1, buildMeta());
        assertNull(chunks.getFirst().getPrevChunkId());
        assertEquals(chunks.getFirst().getNextChunkId(), chunks.get(1).getChunkId());
        assertEquals(chunks.get(1).getPrevChunkId(), chunks.getFirst().getChunkId());
        assertNull(chunks.getLast().getNextChunkId());
    }

    /**
     * 测试解析内联代码
     */
    @Test
    void should_parse_inline_code() {
        String json = """
                {
                    "type": "doc",
                    "content": [
                        {
                            "type": "paragraph",
                            "attrs": {
                                "id": "ad975bf2-96f2-489c-8603-e1783470d0c7",
                                "textAlign": null
                            },
                            "content": [
                                {
                                    "text": "对于代码 ",
                                    "type": "text"
                                },
                                {
                                    "text": "int a = c + b;",
                                    "type": "text",
                                    "marks": [
                                        {
                                            "type": "code"
                                        }
                                    ]
                                },
                                {
                                    "text": " ，可以解释为，a 的值是 c 与 b 的和。这是一个常规的赋值操作，操作类型为整形数字。",
                                    "type": "text"
                                }
                            ]
                        },
                        {
                            "type": "paragraph",
                            "attrs": {
                                "id": "fb95cc4b-632a-433c-8a0a-d7d6f786c4ff",
                                "textAlign": null
                            }
                        }
                    ]
                }
                """;
        List<ChunkDocument> chunks = parser.parse(json, 1, buildMeta());
        assertEquals(1, chunks.size());
        assertEquals(TiptapNodeTypeEnum.PARAGRAPH, chunks.getFirst().getChunkType());
        assertTrue(CharSequenceUtil.contains(chunks.getFirst().getContent(), "对于代码"));
        assertTrue(CharSequenceUtil.contains(chunks.getFirst().getContent(), "int a = c + b;"));
        assertTrue(CharSequenceUtil.contains(chunks.getFirst().getContent(), "操作类型为整形数字。"));
    }

    /**
     * 测试解析代码块
     */
    @Test
    void should_parse_code_block() {
        String json = """
                {
                  "type": "doc",
                  "content": [
                    {
                      "type": "codeBlock",
                      "attrs": {"id": "c1", "language": "java"},
                      "content": [{"type": "text", "text": "System.out.println(\\"hello\\");"}]
                    }
                  ]
                }
                """;
        List<ChunkDocument> chunks = parser.parse(json, 1, buildMeta());
        assertEquals(1, chunks.size());
        assertEquals(TiptapNodeTypeEnum.CODE_BLOCK, chunks.getFirst().getChunkType());
        assertEquals("java", chunks.getFirst().getCodeLanguage());
    }

    @Test
    void should_parse_mermaid_block() {
        String json = """
                {
                  "type": "doc",
                  "content": [{
                    "type": "mermaid",
                    "attrs": {
                      "id": "b14ef589-607e-4bd1-8b5f-05cf4bf2e4aa",
                      "data-content-type": "mermaid"
                    },
                    "content": [
                      { "text": "sequenceDiagram", "type": "text" },
                      { "type": "hardBreak", "attrs": { "id": "30bf349b" } },
                      { "text": "    autonumber", "type": "text" },
                      { "type": "hardBreak", "attrs": { "id": "9be2d76f" } },
                      { "text": "    Alice->>John: Hello John, how are you?", "type": "text" },
                      { "type": "hardBreak", "attrs": { "id": "f88ee354" } },
                      { "text": "    Bob-->>John: Jolly good!", "type": "text" }
                    ]
                  }]
                }
                """;
        List<ChunkDocument> chunks = parser.parse(json, 1, buildMeta());
        assertEquals(1, chunks.size());
        ChunkDocument chunk = chunks.getFirst();
        assertEquals(TiptapNodeTypeEnum.MERMAID, chunk.getChunkType());
        // 验证代码内容正确还原
        assertTrue(CharSequenceUtil.startWith(chunk.getContent(), "[Mermaid图表]\n"));
        assertTrue(CharSequenceUtil.contains(chunk.getContent(), "sequenceDiagram"));
        assertTrue(CharSequenceUtil.contains(chunk.getContent(), "autonumber"));
        assertTrue(CharSequenceUtil.contains(chunk.getContent(), "Alice->>John: Hello John, how are you?"));
        assertTrue(CharSequenceUtil.contains(chunk.getContent(), "Bob-->>John: Jolly good!"));
        // 验证换行结构（hardBreak → \n）
        String code = chunk.getContent().substring("[Mermaid图表]\n".length());
        String[] lines = code.split("\n");
        assertEquals("sequenceDiagram", lines[0]);
        assertEquals("    autonumber", lines[1]);
    }

    @Test
    void should_parse_math_formula() {
        String json = """
                {
                  "type": "doc",
                  "content": [
                        {
                            "type": "paragraph",
                            "attrs": {
                                "id": "75b4da1f-8177-453b-a30f-eb6fd98cb283",
                                "textAlign": null
                            },
                            "content": [
                                {
                                    "text": "行内公式：",
                                    "type": "text"
                                },
                                {
                                    "type": "inlineMath",
                                    "attrs": {
                                        "id": "e15e58f5-7066-45b0-b903-2d56c3d91324",
                                        "latex": "E = mc^2"
                                    }
                                },
                                {
                                    "text": "或",
                                    "type": "text"
                                },
                                {
                                    "type": "inlineMath",
                                    "attrs": {
                                        "id": "9718b1ad-1f2b-4143-8def-a6ef5f0e5441",
                                        "latex": "a^2 = \\\\sqrt{b^2 + c^2}"
                                    }
                                }
                            ]
                        },
                        {
                            "type": "blockMath",
                            "attrs": {
                                "id": "54f07518-fe3f-4bd7-a0fd-e83bd37cd2f1",
                                "latex": "\\\\hat{f} (\\\\xi)=\\\\int_{-\\\\infty}^{\\\\infty}f(x)e^{-2\\\\pi ix\\\\xi}dx"
                            }
                        }
                    ]
                }
                """;
        List<ChunkDocument> chunks = parser.parse(json, 1, buildMeta());
        assertEquals(2, chunks.size());
        assertTrue(CharSequenceUtil.containsAll(chunks.getFirst().getContent(), "行内公式：", "E = mc^2", "a^2 = \\sqrt{b^2 + c^2}"));
        assertEquals(TiptapNodeTypeEnum.BLOCK_MATH, chunks.getLast().getChunkType());
        assertTrue(CharSequenceUtil.contains(chunks.getLast().getContent(), "\\hat{f} (\\xi)=\\int_{-\\infty}^{\\infty}f(x)e^{-2\\pi ix\\xi}dx"));
    }

    @Test
    void should_parse_real_tiptap_json() throws Exception {
        // 使用你提供的完整示例JSON
        String json = loadStandardJson();
        List<ChunkDocument> chunks = parser.parse(json, 100, buildMeta());
        assertFalse(chunks.isEmpty());
        chunks.forEach(c -> {
            assertNotNull(c.getChunkId());
            assertEquals(100, c.getArticleId());
            assertTrue(c.getChunkIndex() >= 0);
        });
        // 验证prev/next链完整
        for (int i = 1; i < chunks.size(); i++) {
            assertEquals(chunks.get(i).getPrevChunkId(), chunks.get(i - 1).getChunkId());
        }
    }

    private ArticleDocument buildMeta() {
        return ArticleDocument.builder()
                .title("Java 进阶指南")
                .author("张三")
                .catalogId(1)
                .tagIds(Set.of(1, 2, 3))
                .articleType("TECH")
                .accessLevel("PUBLIC")
                .isPublic(true)
                .createBy("zhangsc")
                .updateBy("zhangsc")
                .createTime(Instant.now())
                .updateTime(Instant.now())
                .rowVersion(1)
                .build();
    }

    private String loadStandardJson() {
        // 返回你提供的完整 Tiptap JSON 示例
        return ResourceUtil.readUtf8Str("json/richtext/standardTiptapJson.json");
    }
}
