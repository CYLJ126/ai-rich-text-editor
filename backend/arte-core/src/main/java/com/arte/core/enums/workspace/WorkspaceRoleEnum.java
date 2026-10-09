package com.arte.core.enums.workspace;

import com.arte.core.enums.MyEnum;
import com.baomidou.mybatisplus.annotation.IEnum;
import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.Getter;
import org.jspecify.annotations.NonNull;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;

/**
 * 空间治理角色
 * <p>
 * 不隐式授予私有正文读取。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
@Getter
public enum WorkspaceRoleEnum implements IEnum<Integer>, MyEnum<Integer> {
    OWNER(0, "负责人"),
    ADMIN(1, "管理员"),
    MEMBER(2, "成员"),
    GUEST(3, "访客");

    private final Integer value;
    private final String description;

    WorkspaceRoleEnum(int value, String description) {
        this.value = value;
        this.description = description;
    }


    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static WorkspaceRoleEnum fromValue(int value) {
        for (WorkspaceRoleEnum candidate : values()) {
            if (candidate.value == value) return candidate;
        }
        throw new IllegalArgumentException("Unknown WorkspaceRoleEnum value: " + value);
    }

    @ReadingConverter
    public enum IntegerToWorkspaceRoleEnumConverter implements Converter<Integer, WorkspaceRoleEnum> {
        INSTANCE;

        @Override
        public WorkspaceRoleEnum convert(@NonNull Integer source) {
            return fromValue(source);
        }
    }

    @WritingConverter
    public enum WorkspaceRoleEnumToIntegerConverter implements Converter<WorkspaceRoleEnum, Integer> {
        INSTANCE;

        @Override
        public Integer convert(WorkspaceRoleEnum source) {
            return source.getValue();
        }
    }
}
