package com.nip.core.pojo;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Pair;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.nip.core.enums.ResultCodeEnum;
import com.nip.core.utils.ExceptionUtil;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.io.Serial;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 列表查询结果包装器
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2025/6/13 20:39 ✾
 */
@Slf4j
@Getter
@Setter
@ToString
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
public class PageView<T> extends Page<T> implements IResult<T> {
    @Serial
    private static final long serialVersionUID = 6763151138770152002L;

    private String code;
    private String desc;
    private Boolean success;

    public PageView() {
        /* 默认为成功，直接获取列表，当查询失败，则设置为失败返回 */
        this.code = ResultCodeEnum.SUCCESS.getCode();
        this.desc = ResultCodeEnum.SUCCESS.getDesc();
        this.success = Boolean.TRUE;
    }

    public PageView<T> copy() {
        PageView<T> result = new PageView<>();
        result.setCurrent(getCurrent());
        result.setSize(getSize());
        result.setTotal(getTotal());
        result.setCode(getCode());
        result.setDesc(getDesc());
        result.setSuccess(getSuccess());
        result.setRecords(getRecords());
        return result;
    }

    @Override
    public List<T> getRecords() {
        if (CollUtil.isEmpty(super.getRecords())) {
            // 不能返回空列表，否则响应前端时，jackson 序列化时异常
            return null;
        }
        return super.getRecords();
    }

    public static <T> PageView<T> success(IPage<T> page) {
        PageView<T> result = new PageView<>();
        result.setCurrent(page.getCurrent());
        result.setSize(page.getSize());
        result.setTotal(page.getTotal());
        result.setRecords(page.getRecords());
        return result;
    }

    public static <T> PageView<T> empty() {
        PageView<T> result = new PageView<>();
        result.setCurrent(1);
        result.setSize(10);
        result.setTotal(0);
        result.setRecords(Collections.emptyList());
        return result;
    }

    @JsonIgnore
    public T getFirst() {
        return getRecords() == null ? null : getRecords().getFirst();
    }

    @JsonIgnore
    public T getLast() {
        return getRecords() == null ? null : getRecords().getLast();
    }

    public static <T> PageView<T> fail(String desc) {
        return fail(ResultCodeEnum.FAIL, desc);
    }

    public static <T> PageView<T> fail(ResultCodeEnum resultCode) {
        return fail(resultCode, resultCode.getDesc());
    }

    public static <T> PageView<T> fail(ResultCodeEnum resultCode, String desc) {
        PageView<T> result = new PageView<>();
        result.code = resultCode.getCode();
        result.success = Boolean.FALSE;
        result.desc = desc;
        return result;
    }

    public static <T> PageView<T> success(Collection<T> records) {
        PageView<T> result = new PageView<>();
        result.setCurrent(1);
        result.setSize((records.size() / 100 + 1) * 100L);
        result.setTotal(records.size());
        result.setRecords(new ArrayList<>(records));
        return result;
    }

    public static <T> PageView<T> exception(Throwable ex) {
        Pair<ResultCodeEnum, String> errPair = ExceptionUtil.desensitizePair(ex);
        return fail(errPair.getKey(), errPair.getValue());
    }

    public static <R, T> PageView<T> wrap(R req, Function<R, PageView<T>> function) {
        try {
            return function.apply(req);
        } catch (Exception e) {
            log.error(String.format("程序运行出错！请求参数：【%s】", req.toString()), e);
            return exception(e);
        }
    }

    public static <T, U, R> PageView<R> wrap(T req1, U req2, BiFunction<T, U, PageView<R>> function) {
        try {
            return function.apply(req1, req2);
        } catch (Exception e) {
            log.error(String.format("程序运行出错！请求参数：req1: 【%s】，req2: 【%s】", req1.toString(), req2.toString()), e);
            return exception(e);
        }
    }
}
