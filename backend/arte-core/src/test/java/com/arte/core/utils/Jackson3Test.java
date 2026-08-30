package com.arte.core.utils;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.arte.core.enums.StatusEnum;
import lombok.Data;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;

@Slf4j
public class Jackson3Test {


    @Test
    public void testJackson() {

        User user = new User();
        user.name = "zhangsc";
        user.status = StatusEnum.DOING;
        user.createTime = LocalDateTime.now();

        ObjectMapper mapper = new ObjectMapper();
//        ObjectMapper mapper = SerializerFactory.buildJsonMapperWithTypeProperty();
        String json = mapper.writeValueAsString(user);
        log.info("json: {}", json);
        User deserialize = mapper.readValue(json, User.class);
        log.info("deserialize: {}", deserialize);
    }

    @Data
    @ToString
    public static class User {
        private String name;
        private StatusEnum status;
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime createTime;
    }
}
