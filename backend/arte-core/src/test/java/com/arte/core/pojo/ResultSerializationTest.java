package com.arte.core.pojo;

import com.arte.core.serialize.SerializerFactory;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResultSerializationTest {

    private final JsonMapper httpMapper = SerializerFactory.buildJsonMapperWithoutTypeProperty();

    @Test
    void resultContextHttpJsonRoundTripsWithoutJavaTypeMetadata() {
        var result = ResultContext.success(new Payload("hello"));
        String json = httpMapper.writeValueAsString(result);

        assertNoTypeMetadata(json);
        var tree = httpMapper.readTree(json);
        assertTrue(tree.has("data"));
        assertFalse(tree.has("records"));
        assertFalse(tree.has("empty"));
        assertNoTypeMetadata(httpMapper.writerFor(IResult.class).writeValueAsString(result));
        var restored = httpMapper.readValue(json, new TypeReference<ResultContext<Payload>>() {});
        assertEquals(result.getData(), restored.getData());
        assertEquals(result.getCode(), restored.getCode());
        assertEquals(result.getDesc(), restored.getDesc());
        assertEquals(Boolean.TRUE, restored.getSuccess());
    }

    @Test
    void pageViewHttpJsonRoundTripsWithoutJavaTypeMetadata() {
        var result = PageView.success(List.of(new Payload("hello")));
        result.setCurrent(2);
        result.setSize(10);
        result.setTotal(11);
        String json = httpMapper.writeValueAsString(result);

        assertNoTypeMetadata(json);
        var tree = httpMapper.readTree(json);
        assertTrue(tree.has("records"));
        assertFalse(tree.has("data"));
        assertNoTypeMetadata(httpMapper.writerFor(IResult.class).writeValueAsString(result));
        var restored = httpMapper.readValue(json, new TypeReference<PageView<Payload>>() {});
        assertEquals(result.getRecords(), restored.getRecords());
        assertEquals(result.getCurrent(), restored.getCurrent());
        assertEquals(result.getSize(), restored.getSize());
        assertEquals(result.getTotal(), restored.getTotal());
        assertEquals(result.getCode(), restored.getCode());
        assertEquals(result.getDesc(), restored.getDesc());
        assertEquals(Boolean.TRUE, restored.getSuccess());
    }

    @Test
    void streamResponsesAlsoOmitJavaTypeMetadata() {
        var mapper = SerializerFactory.buildStreamJsonMapper();
        assertNoTypeMetadata(mapper.writeValueAsString(ResultContext.success(new Payload("hello"))));
        assertNoTypeMetadata(mapper.writeValueAsString(PageView.success(List.of(new Payload("hello")))));
    }

    @Test
    void cacheMapperStillRestoresResponseAndPayloadTypes() {
        var mapper = SerializerFactory.buildJsonMapperWithTypeProperty();
        var result = ResultContext.success(new Payload("hello"));
        var page = PageView.success(List.of(new Payload("hello")));

        String resultJson = mapper.writeValueAsString(result);
        String pageJson = mapper.writeValueAsString(page);
        assertTrue(resultJson.contains("\"@class\""));
        assertTrue(pageJson.contains("\"@class\""));

        var restoredResult = assertInstanceOf(ResultContext.class, mapper.readValue(resultJson, Object.class));
        assertEquals(result.getData(), restoredResult.getData());
        assertEquals(result.getCode(), restoredResult.getCode());
        var restoredPage = assertInstanceOf(PageView.class, mapper.readValue(pageJson, Object.class));
        assertEquals(page.getRecords(), restoredPage.getRecords());
        assertEquals(page.getTotal(), restoredPage.getTotal());
    }

    @Test
    void concreteHttpResponseCanBeReadWithoutClassProperty() {
        var result = httpMapper.readValue("""
                {"code":"100000","success":true,"desc":"成功","data":{"value":"hello"}}
                """, new TypeReference<ResultContext<Payload>>() {});
        var page = httpMapper.readValue("""
                {"code":"100000","success":true,"desc":"成功","current":1,"size":10,
                 "total":1,"records":[{"value":"hello"}]}
                """, new TypeReference<PageView<Payload>>() {});

        assertEquals(new Payload("hello"), result.getData());
        assertEquals(List.of(new Payload("hello")), page.getRecords());
    }

    private static void assertNoTypeMetadata(String json) {
        assertFalse(json.contains("\"@class\""), json);
        assertFalse(json.contains("com.arte."), json);
    }

    public record Payload(String value) {}
}
