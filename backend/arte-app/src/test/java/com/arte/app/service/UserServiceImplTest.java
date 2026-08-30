package com.arte.app.service;

import com.arte.app.api.rbac.UserService;
import com.arte.app.pojo.rbac.UserDto;
import com.arte.core.enums.StatusEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
@SpringBootTest()
class UserServiceImplTest {

    @Resource
    UserService userService;

    @Test
    void addUser() {
        UserDto user = new UserDto();
        user.setUserName("张三");
        user.setMobile("Zhangs");
        user.setStatus(StatusEnum.DOING);
        user.setEmail("zhangsan@example.com");
        user.setPassword("<PASSWORD>");
        user.setDescription("张三");
        user.setCreateBy("1");
        user.setUpdateBy("1");
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        user.setRowVersion(1);
        assertTrue(userService.addUser(user));
    }
}