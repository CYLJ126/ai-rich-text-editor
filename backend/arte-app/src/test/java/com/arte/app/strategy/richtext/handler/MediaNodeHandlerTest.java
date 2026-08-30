package com.arte.app.strategy.richtext.handler;

import com.arte.ai.api.EmbeddingService;
import com.arte.app.common.utils.MediaRefUtil;
import com.arte.app.pojo.richtext.ArticleDocument;
import com.arte.app.pojo.richtext.ChunkDocument;
import com.arte.app.pojo.richtext.TiptapNode;
import com.arte.app.service.richtext.TiptapJsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MediaNodeHandlerTest {

    @Test
    void should_parse_consecutive_audio_video_and_image_nodes_into_one_chunk() {
        TiptapJsonParser parser = new TiptapJsonParser(
                List.of(new MediaNodeHandler()),
                new EmptyEmbeddingService()
        );
        String json = """
                {
                  "type": "doc",
                  "content": [
                    {
                      "type": "audio",
                      "attrs": {
                        "id": "35fbd068-5720-488d-af0a-659167f33020",
                        "src": "https://samplelib.com/lib/preview/mp3/sample-3s.mp3"
                      }
                    },
                    {
                      "type": "video",
                      "attrs": {
                        "id": "910291b9-dfd1-4a62-b2c3-63e69b55ee49",
                        "src": "https://www.bilibili.com/video/BV1jW41137GG/?spm_id_from=333.337",
                        "title": "视频播放器"
                      }
                    },
                    {
                      "type": "image",
                      "attrs": {
                        "id": "a5153a58-7284-4c58-be10-76093cd605ce",
                        "src": "https://fastly.picsum.photos/id/187/400/300.jpg",
                        "alt": "随机图片",
                        "title": null
                      }
                    }
                  ]
                }
                """;

        List<ChunkDocument> chunks = parser.parse(json, 43, ArticleDocument.builder().build());

        assertEquals(1, chunks.size());
        assertEquals(
                "[音频: src=https://samplelib.com/lib/preview/mp3/sample-3s.mp3] "
                        + "[视频: src=https://www.bilibili.com/video/BV1jW41137GG/?spm_id_from=333.337; title=视频播放器] "
                        + "[图片: src=https://fastly.picsum.photos/id/187/400/300.jpg; alt=随机图片]",
                chunks.getFirst().getContent()
        );
        assertEquals(
                List.of(
                        "[音频: src=https://samplelib.com/lib/preview/mp3/sample-3s.mp3]",
                        "[视频: src=https://www.bilibili.com/video/BV1jW41137GG/?spm_id_from=333.337; title=视频播放器]",
                        "[图片: src=https://fastly.picsum.photos/id/187/400/300.jpg; alt=随机图片]"
                ),
                chunks.getFirst().getMediaRefs()
        );
    }

    @Test
    void should_include_all_src_title_and_alt_attributes() {
        TiptapNode node = new TiptapNode();
        node.setType("image");
        node.setAttrs(Map.of(
                "src", "https://example.com/cover.png",
                "title", "文章封面",
                "alt", "蓝色封面图"
        ));

        assertEquals(
                "[图片: src=https://example.com/cover.png; title=文章封面; alt=蓝色封面图]",
                MediaRefUtil.buildRef(node)
        );
    }

    private static class EmptyEmbeddingService implements EmbeddingService {
        @Override
        public int getDimension() {
            return 0;
        }

        @Override
        public float[] generateEmbedding(String text) {
            return new float[0];
        }
    }
}
