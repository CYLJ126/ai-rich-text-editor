package com.arte.core.pojo;

import cn.hutool.core.lang.Pair;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.i18n.MessageUtils;
import com.arte.core.utils.ExceptionUtil;
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
public class ResultContext<T> implements IResult, Serializable {
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
        // desc 支持传 message key，按当前语言解析；未收录的原始文本原样返回
        this.desc = MessageUtils.get(desc);
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
        result.setDesc(desc);
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
        result.setDesc(desc);
        return result;
    }

    /**
     * 执行单参数同步调用，将返回值包装为成功响应，异常包装为失败响应。
     *
     * @param req      请求参数
     * @param function 同步业务函数
     * @param <R>      请求参数类型
     * @param <T>      业务返回值类型
     * @return 包装后的成功或失败响应
     * @apiNote 仅捕获当前线程调用过程中同步抛出的 {@link Exception}。
     * 如果函数返回 {@code Publisher}，它只会被当作 data 保存，不会被订阅，
     * 也不会捕获其订阅阶段的异常。响应式调用应在链路中使用 {@code map} 包装成功结果、
     * 使用 {@code onErrorResume} 包装失败结果；其他异步任务的异常也应在异步调用侧处理。
     */
    public static <R, T> ResultContext<T> wrap(R req, Function<R, T> function) {
        try {
            T resp = function.apply(req);
            return success(resp);
        } catch (Exception e) {
            log.error("业务调用失败", e);
            return exception(e);
        }
    }

    /**
     * 执行无参数同步调用，将返回值包装为成功响应，异常包装为失败响应。
     *
     * @param supplier 同步业务函数
     * @param <T>      业务返回值类型
     * @return 包装后的成功或失败响应
     * @apiNote 仅捕获当前线程调用过程中同步抛出的 {@link Exception}。
     * 如果函数返回 {@code Publisher}，它只会被当作 data 保存，不会被订阅，
     * 也不会捕获其订阅阶段的异常。响应式调用应在链路中使用 {@code map} 包装成功结果、
     * 使用 {@code onErrorResume} 包装失败结果；其他异步任务的异常也应在异步调用侧处理。
     */
    public static <T> ResultContext<T> wrap(Supplier<T> supplier) {
        try {
            T resp = supplier.get();
            return success(resp);
        } catch (Exception e) {
            log.error("程序运行出错！", e);
            return exception(e);
        }
    }

    /**
     * 执行无返回值的同步调用，正常完成时返回无 data 的成功响应，异常包装为失败响应。
     *
     * @param req      请求参数
     * @param consumer 同步业务操作
     * @param <R>      请求参数类型
     * @return 无业务返回值的成功或失败响应
     * @apiNote 仅捕获当前线程调用过程中同步抛出的 {@link Exception}。
     * 正常返回仅表示 consumer 已返回，不表示其启动的异步任务已完成。
     * 本方法不会订阅 {@code Publisher}，也不会捕获订阅或其他异步执行阶段的异常；
     * 响应式调用应在链路中包装完成结果，并使用 {@code onErrorResume} 包装失败结果。
     */
    public static <R> ResultContext<Void> wrap(R req, Consumer<R> consumer) {
        try {
            consumer.accept(req);
            return success();
        } catch (Exception e) {
            log.error("程序运行出错！", e);
            return exception(e);
        }
    }

    /**
     * 执行双参数同步调用，将返回值包装为成功响应，异常包装为失败响应。
     *
     * @param req1     第一个请求参数
     * @param req2     第二个请求参数
     * @param function 同步业务函数
     * @param <T>      第一个请求参数类型
     * @param <U>      第二个请求参数类型
     * @param <R>      业务返回值类型
     * @return 包装后的成功或失败响应
     * @apiNote 仅捕获当前线程调用过程中同步抛出的 {@link Exception}。
     * 如果函数返回 {@code Publisher}，它只会被当作 data 保存，不会被订阅，
     * 也不会捕获其订阅阶段的异常。响应式调用应在链路中使用 {@code map} 包装成功结果、
     * 使用 {@code onErrorResume} 包装失败结果；其他异步任务的异常也应在异步调用侧处理。
     */
    public static <T, U, R> ResultContext<R> wrap(T req1, U req2, BiFunction<T, U, R> function) {
        try {
            R resp = function.apply(req1, req2);
            return success(resp);
        } catch (Exception e) {
            log.error("业务调用失败", e);
            return exception(e);
        }
    }
}
