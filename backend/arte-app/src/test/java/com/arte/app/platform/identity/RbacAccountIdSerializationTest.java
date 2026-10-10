package com.arte.app.platform.identity;

import com.arte.app.pojo.rbac.UserDto;
import com.arte.app.pojo.rbac.param.RoleParam;
import com.arte.app.pojo.rbac.param.UserParam;
import com.arte.core.pojo.UserOnlineInfo;
import com.arte.core.serialize.SerializerFactory;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.redisson.client.handler.State;
import org.redisson.codec.JsonJackson3Codec;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 使用与 HTTP、Redis 相同的序列化配置，验证账号 ID 精度及旧数字字段兼容。
 */
class RbacAccountIdSerializationTest {
    private final tools.jackson.databind.json.JsonMapper http = SerializerFactory.buildJsonMapperWithoutTypeProperty();

    @Test
    void accountAndSessionIdsUseDecimalStringsThroughHttp() {
        long id = Long.MAX_VALUE;
        var user = new UserDto();
        user.setId(id);
        var session = new UserOnlineInfo().setId(id);
        for (var value : new Object[]{user, session}) {
            var json = http.writeValueAsString(value);
            assertEquals('"' + Long.toString(id) + '"', http.readTree(json).get("id").toString());
        }
        assertEquals(id, http.readValue(http.writeValueAsString(user), UserDto.class).getId());
        assertEquals(id, http.readValue(http.writeValueAsString(session), UserOnlineInfo.class).getId());
    }

    @Test
    void userQueryRetainsLongIdAndInheritedQueryFieldsWithoutChangingRoleIds() {
        var query = http.readValue("""
                {"id":"9223372036854775807","current":3,"size":12,"createBy":"alice",
                 "createTimeFloor":"2026-10-10 10:00:00","tags":[7],"userName":"alice"}
                """, UserParam.class);
        assertEquals(Long.MAX_VALUE, query.getId());
        assertEquals(3, query.getCurrent());
        assertEquals(12, query.getSize());
        assertEquals("alice", query.getCreateBy());
        assertNotNull(query.getCreateTimeFloor());
        assertTrue(query.getTags().contains(7));
        assertEquals("alice", query.getUserName());
        assertEquals('"' + Long.toString(Long.MAX_VALUE) + '"', http.readTree(http.writeValueAsString(query)).get("id").toString());
        var role = http.readValue("{\"id\":7,\"current\":3,\"createBy\":\"alice\"}", RoleParam.class);
        assertEquals(Integer.valueOf(7), role.getId());
        assertEquals(3, role.getCurrent());
        assertEquals("alice", role.getCreateBy());
        assertEquals("7", http.readTree(http.writeValueAsString(role)).get("id").toString());
    }

    @Test
    void oldNumericInputsRemainReadableAndOverflowIsRejected() {
        assertEquals(7L, http.readValue("{\"id\":7}", UserDto.class).getId());
        assertEquals(7L, http.readValue("{\"id\":7}", UserOnlineInfo.class).getId());
        assertEquals(7L, http.readValue("{\"id\":7}", UserParam.class).getId());
        for (var type : new Class<?>[]{UserDto.class, UserOnlineInfo.class, UserParam.class}) {
            assertThrows(RuntimeException.class, () -> http.readValue("{\"id\":\"9223372036854775808\"}", type));
        }
    }

    @Test
    void actualRedisCodecReadsOldNumericAccountAndSessionIdsAsLong() throws Exception {
        var codec = new JsonJackson3Codec(SerializerFactory.buildJsonMapperWithTypeProperty());
        var user = new UserDto();
        user.setId(7L);
        var session = new UserOnlineInfo().setId(7L);
        for (var value : new Object[]{user, session}) {
            var encoded = codec.getValueEncoder().encode(value);
            String oldJson;
            try {
                // 旧缓存采用相同的类型元数据，Integer 字段在 JSON 中是普通数字。
                oldJson = encoded.toString(StandardCharsets.UTF_8).replaceAll("\"id\"\\s*:\\s*\"7\"", "\"id\":7");
                assertTrue(oldJson.contains("\"id\":7"));
            } finally {
                encoded.release();
            }
            var oldBuffer = Unpooled.copiedBuffer(oldJson, StandardCharsets.UTF_8);
            try {
                var restored = codec.getValueDecoder().decode(oldBuffer, new State());
                if (restored instanceof UserDto dto) assertEquals(Long.valueOf(7), dto.getId());
                else assertEquals(Long.valueOf(7), ((UserOnlineInfo) restored).getId());
            } finally {
                oldBuffer.release();
            }
        }
    }

    @Test
    void actualRedisCodecRoundTripsFullLongAccountAndSessionIds() throws Exception {
        var codec = new JsonJackson3Codec(SerializerFactory.buildJsonMapperWithTypeProperty());
        var user = new UserDto();
        user.setId(Long.MAX_VALUE);
        for (var value : new Object[]{user, new UserOnlineInfo().setId(Long.MAX_VALUE)}) {
            var buffer = codec.getValueEncoder().encode(value);
            try {
                var restored = codec.getValueDecoder().decode(buffer, new State());
                if (restored instanceof UserDto dto) assertEquals(Long.valueOf(Long.MAX_VALUE), dto.getId());
                else assertEquals(Long.valueOf(Long.MAX_VALUE), ((UserOnlineInfo) restored).getId());
            } finally {
                buffer.release();
            }
        }
    }
}
