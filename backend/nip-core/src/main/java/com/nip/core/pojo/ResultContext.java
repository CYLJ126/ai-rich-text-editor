package com.nip.core.pojo;

import cn.hutool.core.lang.Pair;
import com.nip.core.enums.ResultCodeEnum;
import com.nip.core.utils.ExceptionUtil;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 返回结果包装，用于系统间调用、前后端调用
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2024/7/12 23:24 ✾
 */
@Getter
@Setter
@ToString
@Accessors(chain = true)
@Slf4j
public class ResultContext<T> implements IResult<T>, Serializable {
    @Serial
    private static final long serialVersionUID = 71449509739969614L;

    private String code;
    private String desc;
    private Boolean success;
    private T data;

    @Override
    public ResultContext<T> setCode(String code) {
        this.code = code;
        return this;
    }

    @Override
    public ResultContext<T> setDesc(String desc) {
        this.desc = desc;
        return this;
    }

    @Override
    public ResultContext<T> setSuccess(Boolean success) {
        this.success = success;
        return this;
    }

    /**
     * 统计信息
     */
    private Map<Serializable, Serializable> statistics;

    public static <T> ResultContext<T> partialSuccess(long total, long success) {
        ResultContext<T> resultContext = success();
        resultContext.setStatistics(Map.of("total", total, "success", success, "fail", total - success));
        return resultContext;
    }

    public static <T> ResultContext<T> success() {
        return success(null);
    }

    public static <T> ResultContext<T> success(T data) {
        return success(data, ResultCodeEnum.SUCCESS, ResultCodeEnum.SUCCESS.getDesc());
    }

    public static <T> ResultContext<T> success(T data, ResultCodeEnum resultCode) {
        return success(data, resultCode, resultCode.getDesc());
    }

    public static <T> ResultContext<T> success(T data, String desc) {
        return success(data, ResultCodeEnum.SUCCESS, desc);
    }

    public static <T> ResultContext<T> success(T data, ResultCodeEnum resultCode, String desc) {
        ResultContext<T> result = new ResultContext<>();
        result.code = resultCode.getCode();
        result.success = Boolean.TRUE;
        result.desc = desc;
        result.setData(data);
        return result;
    }

    public static <T> ResultContext<T> exception() {
        return fail(ResultCodeEnum.EXCEPTION, ResultCodeEnum.EXCEPTION.getDesc());
    }

    public static <T> ResultContext<T> exception(Throwable ex) {
        Pair<ResultCodeEnum, String> errPair = ExceptionUtil.desensitizePair(ex);
        return fail(errPair.getKey(), errPair.getValue());
    }

    public static <T> ResultContext<T> fail() {
        return fail(ResultCodeEnum.FAIL, ResultCodeEnum.FAIL.getDesc());
    }

    public static <T> ResultContext<T> fail(ResultCodeEnum resultCode) {
        return fail(resultCode, resultCode.getDesc());
    }

    public static <T> ResultContext<T> fail(String desc) {
        return fail(ResultCodeEnum.FAIL, desc);
    }

    public static <T> ResultContext<T> fail(String format, Object... args) {
        String message = String.format(format, args);
        return fail(ResultCodeEnum.FAIL, message);
    }

    public static <T> ResultContext<T> fail(ResultCodeEnum resultCode, String desc) {
        ResultContext<T> result = new ResultContext<>();
        result.code = resultCode.getCode();
        result.success = Boolean.FALSE;
        result.desc = desc;
        return result;
    }

    public static <R, T> ResultContext<T> wrap(R req, Function<R, T> function) {
        try {
            T resp = function.apply(req);
            return success(resp);
        } catch (Exception e) {
            log.error(String.format("程序运行出错！请求参数：【%s】", req.toString()), e);
            return exception(e);
        }
    }

    public static <T> ResultContext<T> wrap(Supplier<T> supplier) {
        try {
            T resp = supplier.get();
            return success(resp);
        } catch (Exception e) {
            log.error("程序运行出错！", e);
            return exception(e);
        }
    }

    public static <R> ResultContext<Void> wrap(R req, Consumer<R> consumer) {
        try {
            consumer.accept(req);
            return success();
        } catch (Exception e) {
            log.error("程序运行出错！", e);
            return exception(e);
        }
    }

    public static <T, U, R> ResultContext<R> wrap(T req1, U req2, BiFunction<T, U, R> function) {
        try {
            R resp = function.apply(req1, req2);
            return success(resp);
        } catch (Exception e) {
            log.error(String.format("程序运行出错！请求参数：req1: 【%s】，req2: 【%s】", req1.toString(), req2.toString()), e);
            return exception(e);
        }
    }
}
