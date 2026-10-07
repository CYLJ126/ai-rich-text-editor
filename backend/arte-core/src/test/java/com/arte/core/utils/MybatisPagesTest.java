package com.arte.core.utils;

import com.arte.core.pojo.PageParam;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MybatisPagesTest {

    @Test
    void invalidPagesAreRejectedWithoutHttpValidation() {
        assertThrows(IllegalArgumentException.class, () -> MybatisPages.from(null));

        PageParam missingCurrent = new PageParam();
        missingCurrent.setCurrent(null);
        assertThrows(IllegalArgumentException.class, () -> MybatisPages.from(missingCurrent));
        PageParam missingSize = new PageParam();
        missingSize.setSize(null);
        assertThrows(IllegalArgumentException.class, () -> MybatisPages.from(missingSize));

        for (long current : List.of(0L, -1L)) {
            PageParam param = new PageParam();
            param.setCurrent(current);
            assertThrows(IllegalArgumentException.class, () -> MybatisPages.from(param));
        }
        for (long size : List.of(0L, -1L, PageParam.MAX_SIZE + 1, Long.MAX_VALUE)) {
            PageParam param = new PageParam();
            param.setSize(size);
            assertThrows(IllegalArgumentException.class, () -> MybatisPages.from(param));
        }
    }

    @Test
    void eachConversionCreatesAnIndependentQueryContainer() {
        PageParam param = new PageParam();
        param.setCurrent(2L);
        param.setSize(PageParam.MAX_SIZE);

        var first = MybatisPages.<String>from(param);
        var second = MybatisPages.<String>from(param);
        first.setCurrent(3);
        first.setTotal(250);
        first.setRecords(List.of("answer"));

        assertNotSame(first, second);
        assertEquals(2L, param.getCurrent());
        assertEquals(2L, second.getCurrent());
        assertEquals(PageParam.MAX_SIZE, second.getSize());
        assertEquals(0L, second.getTotal());
        assertTrue(second.getRecords().isEmpty());
    }
}
