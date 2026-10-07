package com.arte.core.pojo;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PageViewTest {

    @Test
    void collectionOperationsUsePageRecords() {
        var page = PageView.success(List.of("first", "second"));
        assertFalse(page.isEmpty());
        assertEquals(List.of("FIRST", "SECOND"), page.stream().map(String::toUpperCase).toList());

        var visited = new ArrayList<String>();
        page.forEach(visited::add);
        assertEquals(List.of("first", "second"), visited);
    }

    @Test
    void collectionOperationsHandleEmptyPage() {
        var page = PageView.<String>empty();
        assertTrue(page.isEmpty());
        assertEquals(0, page.stream().count());
        page.forEach(value -> fail("empty pages must not invoke the consumer"));
    }
}
