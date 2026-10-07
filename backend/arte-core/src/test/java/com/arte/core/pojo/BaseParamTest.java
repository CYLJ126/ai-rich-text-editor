package com.arte.core.pojo;

import com.arte.core.serialize.SerializerFactory;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class BaseParamTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;
    private final JsonMapper mapper = SerializerFactory.buildJsonMapperWithoutTypeProperty();

    @BeforeAll
    static void setUpValidation() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidation() {
        validatorFactory.close();
    }

    @Test
    void businessQueryRoundTripsWithoutResponseOrDatabaseFields() {
        RoleQuery query = mapper.readValue("""
                {"roleCode":"admin","keyword":"管理员", "page":{"current":2,"size":10},
                 "createTime":{"from":"2026-10-01T00:00:00.123456789+08:00",
                               "to":"2026-10-08T00:00:00+08:00"},
                 "updateTime":{"from":"2026-10-05T00:00:00Z",
                               "to":"2026-10-08T00:00:00Z"}}
                """, RoleQuery.class);

        assertTrue(validator.validate(query).isEmpty());
        String json = mapper.writeValueAsString(query);
        assertFalse(json.contains("\"@class\""));
        assertFalse(json.contains("\"createTimeRangeValid\""));
        assertFalse(json.contains("\"updateTimeRangeValid\""));
        assertFalse(json.contains("\"rangeValid\""));
        assertFalse(json.contains("\"createTimeFloor\""));
        assertFalse(json.contains("\"createTimeCeil\""));
        assertFalse(json.contains("\"createTimeFrom\""));
        assertFalse(json.contains("\"updateTimeTo\""));
        assertFalse(json.contains("\"records\""));
        assertFalse(json.contains("\"total\""));
        assertFalse(json.contains("\"success\""));
        assertFalse(json.contains("\"searchCount\""));

        RoleQuery restored = mapper.readValue(json, RoleQuery.class);
        assertEquals("admin", restored.getRoleCode());
        assertEquals("管理员", restored.getKeyword());
        assertEquals(2L, restored.getPage().getCurrent());
        assertEquals(10L, restored.getPage().getSize());
        assertEquals(Instant.parse("2026-09-30T16:00:00.123456789Z"), restored.getCreateTime().getFrom());
        assertEquals(Instant.parse("2026-10-07T16:00:00Z"), restored.getCreateTime().getTo());
        assertEquals(Instant.parse("2026-10-05T00:00:00Z"), restored.getUpdateTime().getFrom());
        assertEquals(Instant.parse("2026-10-08T00:00:00Z"), restored.getUpdateTime().getTo());
    }

    @Test
    void omittedPaginationUsesDefaultsAndAllowsPartialPageObjects() {
        BaseParam defaults = mapper.readValue("{}", BaseParam.class);
        assertTrue(validator.validate(defaults).isEmpty());
        assertEquals(1L, defaults.getPage().getCurrent());
        assertEquals(20L, defaults.getPage().getSize());

        BaseParam partial = mapper.readValue("{\"page\":{\"current\":3}}", BaseParam.class);
        assertTrue(validator.validate(partial).isEmpty());
        assertEquals(3L, partial.getPage().getCurrent());
        assertEquals(20L, partial.getPage().getSize());
        assertNotSame(defaults.getPage(), partial.getPage());
    }

    @Test
    void explicitNullPageIsRejected() {
        BaseParam query = mapper.readValue("{\"page\":null}", BaseParam.class);
        assertEquals(Set.of("page"), invalidPaths(query));
    }

    @Test
    void explicitNullPageFieldsAreRejected() {
        BaseParam query = mapper.readValue("{\"page\":{\"current\":null,\"size\":null}}", BaseParam.class);
        assertEquals(Set.of("page.current", "page.size"), invalidPaths(query));
    }

    @Test
    void inheritedConstraintsValidateNestedPageKeywordAndTimeRange() {
        RoleQuery query = mapper.readValue("""
                {"page":{"current":0,"size":101},
                 "createTime":{"from":"2026-10-07T00:00:00Z","to":"2026-10-01T00:00:00Z"},
                 "updateTime":{"from":"2026-10-07T00:00:00Z","to":"2026-10-01T00:00:00Z"}}
                """, RoleQuery.class);
        query.setKeyword("x".repeat(201));

        assertEquals(Set.of("page.current", "page.size", "keyword", "createTime.rangeValid", "updateTime.rangeValid"),
                invalidPaths(query));
    }

    @Test
    void oneSidedAndEqualTimeBoundsAreValid() {
        BaseParam query = new BaseParam();
        Instant boundary = Instant.parse("2026-10-07T00:00:00Z");
        InstantRangeParam createTime = new InstantRangeParam();
        query.setCreateTime(createTime);
        assertTrue(validator.validate(query).isEmpty());
        createTime.setFrom(boundary);
        assertTrue(validator.validate(query).isEmpty());
        createTime.setTo(boundary);
        assertTrue(validator.validate(query).isEmpty());
        createTime.setFrom(null);
        assertTrue(validator.validate(query).isEmpty());
        InstantRangeParam updateTime = new InstantRangeParam();
        query.setUpdateTime(updateTime);
        updateTime.setFrom(boundary);
        assertTrue(validator.validate(query).isEmpty());
        updateTime.setTo(boundary);
        assertTrue(validator.validate(query).isEmpty());
        updateTime.setFrom(null);
        assertTrue(validator.validate(query).isEmpty());
    }

    @Test
    void omittedAndNullTimeRangesLeaveFiltersUnset() {
        BaseParam omitted = mapper.readValue("{}", BaseParam.class);
        BaseParam explicitNull = mapper.readValue("{\"createTime\":null,\"updateTime\":null}", BaseParam.class);
        for (BaseParam query : new BaseParam[]{omitted, explicitNull}) {
            assertNull(query.getCreateTime());
            assertNull(query.getUpdateTime());
            assertTrue(validator.validate(query).isEmpty());
        }
    }

    @Test
    void instantBoundsAreComparedAcrossOffsetsAndRequireATimeZone() {
        BaseParam differentOffsets = mapper.readValue("""
                {"createTime":{"from":"2026-10-07T00:00:00+08:00","to":"2026-10-06T17:00:00Z"},
                 "updateTime":{"from":"2026-10-07T00:00:00+08:00","to":"2026-10-06T16:00:00Z"}}
                """, BaseParam.class);
        assertTrue(validator.validate(differentOffsets).isEmpty());
        assertEquals(differentOffsets.getUpdateTime().getFrom(), differentOffsets.getUpdateTime().getTo());
        assertThrows(RuntimeException.class, () -> mapper.readValue("""
                {"createTime":{"from":"2026-10-07T00:00:00"}}
                """, BaseParam.class));
    }

    @Test
    void businessDateAndInstantComponentsRoundTripAndValidateIndependently() {
        BusinessQuery query = mapper.readValue("""
                {"startTime":{"from":"2026-10-07T00:00:00+08:00"},
                 "endTime":{"to":"2026-10-08T00:00:00Z"},
                 "startDate":{"from":"2026-10-01","to":"2026-11-01"},
                 "endDate":{"from":"2026-10-07","to":"2026-10-07"}}
                """, BusinessQuery.class);
        assertTrue(validator.validate(query).isEmpty());
        String json = mapper.writeValueAsString(query);
        assertFalse(json.contains("\"rangeValid\""));
        BusinessQuery restored = mapper.readValue(json, BusinessQuery.class);
        assertEquals(Instant.parse("2026-10-06T16:00:00Z"), restored.getStartTime().getFrom());
        assertNull(restored.getStartTime().getTo());
        assertNull(restored.getEndTime().getFrom());
        assertEquals(Instant.parse("2026-10-08T00:00:00Z"), restored.getEndTime().getTo());
        assertEquals(LocalDate.of(2026, 10, 1), restored.getStartDate().getFrom());
        assertEquals(LocalDate.of(2026, 11, 1), restored.getStartDate().getTo());
        assertEquals(LocalDate.of(2026, 10, 7), restored.getEndDate().getFrom());
        assertEquals(restored.getEndDate().getFrom(), restored.getEndDate().getTo());

        restored.getStartTime().setTo(Instant.parse("2026-10-06T15:00:00Z"));
        restored.getEndTime().setFrom(Instant.parse("2026-10-09T00:00:00Z"));
        restored.getStartDate().setTo(LocalDate.of(2026, 9, 30));
        restored.getEndDate().setTo(LocalDate.of(2026, 10, 6));
        assertEquals(Set.of("startTime.rangeValid", "endTime.rangeValid", "startDate.rangeValid", "endDate.rangeValid"),
                invalidPaths(restored));
    }

    @Test
    void businessDateRangesAllowOneSidedAndUnboundedFilters() {
        BusinessQuery query = mapper.readValue("""
                {"startDate":{"from":"2026-10-07"},"endDate":{"to":"2026-10-08"}}
                """, BusinessQuery.class);
        assertTrue(validator.validate(query).isEmpty());
        query.setStartDate(new DateRangeParam());
        query.setEndDate(null);
        assertTrue(validator.validate(query).isEmpty());
    }

    private static Set<String> invalidPaths(BaseParam query) {
        return validator.validate(query).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    public static class RoleQuery extends BaseParam {
        private String roleCode;

        public String getRoleCode() { return roleCode; }
        public void setRoleCode(String roleCode) { this.roleCode = roleCode; }
    }

    /** 直接使用继承字段，验证基类的 JSON 映射及级联校验。 */
    public static class BusinessQuery extends BaseParam {}
}
