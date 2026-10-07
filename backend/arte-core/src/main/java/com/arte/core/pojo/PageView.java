package com.arte.core.pojo;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Pair;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.i18n.MessageUtils;
import com.arte.core.utils.ExceptionUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.i18n.LocaleContextHolder;

import java.io.Serial;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * 列表查询结果包装器
 * <p>
 * desc 保存最终展示文本，setter 不负责翻译。
 * 未指定 Locale 的工厂方法使用调用线程语言；异步或响应式调用应在 HTTP 入口取得 Locale，
 * 传入显式 Locale 工厂方法。copy 和 JSON 反序列化保留已有 desc，不再次翻译。
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
public class PageView<T> extends Page<T> implements IResult {
    @Serial
    private static final long serialVersionUID = 6763151138770152002L;

    private String code;
    private String desc;
    private Boolean success;

    public PageView() {
        this(LocaleContextHolder.getLocale());
    }

    /**
     * 按指定语言初始化分页响应。
     */
    public PageView(Locale locale) {
        /* 默认为成功，直接获取列表，当查询失败，则设置为失败返回 */
        this.code = ResultCodeEnum.SUCCESS.getCode();
        this.desc = ResultCodeEnum.SUCCESS.getDesc(locale);
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
            return Collections.emptyList();
        }
        return super.getRecords();
    }

    public Stream<T> stream() {
        List<T> records = getRecords();
        return records == null ? Stream.empty() : records.stream();
    }

    public boolean isEmpty() {
        return CollUtil.isEmpty(getRecords());
    }

    public void forEach(Consumer<? super T> action) {
        List<T> records = getRecords();
        if (CollUtil.isNotEmpty(records)) {
            records.forEach(action);
        }
    }

    public static <T> PageView<T> success(IPage<T> page) {
        return success(page, LocaleContextHolder.getLocale());
    }

    /**
     * 按显式请求语言将查询分页转换为成功响应。
     */
    public static <T> PageView<T> success(IPage<T> page, Locale locale) {
        PageView<T> result = new PageView<>(locale);
        result.setCurrent(page.getCurrent());
        result.setSize(page.getSize());
        result.setTotal(page.getTotal());
        result.setRecords(page.getRecords());
        return result;
    }

    public static <T> PageView<T> empty() {
        return empty(LocaleContextHolder.getLocale());
    }

    /**
     * 按显式请求语言构建空分页响应。
     */
    public static <T> PageView<T> empty(Locale locale) {
        PageView<T> result = new PageView<>(locale);
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
        return fail(resultCode, desc, LocaleContextHolder.getLocale());
    }

    /**
     * 按显式请求语言构建标准失败响应。
     */
    public static <T> PageView<T> fail(ResultCodeEnum resultCode, Locale locale) {
        return fail(resultCode, resultCode.getDesc(locale), locale);
    }

    /**
     * 按显式请求语言解析 message key；未收录的文本原样保存。
     */
    public static <T> PageView<T> fail(ResultCodeEnum resultCode, String desc, Locale locale) {
        PageView<T> result = new PageView<>(locale);
        result.code = resultCode.getCode();
        result.success = Boolean.FALSE;
        result.setDesc(MessageUtils.get(locale, desc));
        return result;
    }

    public static <T> PageView<T> success(Collection<T> records) {
        return success(records, LocaleContextHolder.getLocale());
    }

    /**
     * 按显式请求语言构建集合成功响应。
     */
    public static <T> PageView<T> success(Collection<T> records, Locale locale) {
        PageView<T> result = new PageView<>(locale);
        result.setCurrent(1);
        result.setSize((records.size() / 100 + 1) * 100L);
        result.setTotal(records.size());
        result.setRecords(new ArrayList<>(records));
        return result;
    }

    public static <T> PageView<T> exception(Throwable ex) {
        return exception(ex, LocaleContextHolder.getLocale());
    }

    /**
     * 按显式请求语言构建安全异常响应，不回传原始异常消息。
     */
    public static <T> PageView<T> exception(Throwable ex, Locale locale) {
        Pair<ResultCodeEnum, String> errPair = ExceptionUtil.desensitizePair(ex, locale);
        return fail(errPair.getKey(), errPair.getValue(), locale);
    }

    /**
     * 执行单参数同步分页调用，直接返回业务分页结果，异常包装为分页失败响应。
     *
     * @param req      请求参数
     * @param function 返回分页结果的同步业务函数
     * @param <R>      请求参数类型
     * @param <T>      分页记录类型
     * @return 业务分页结果或分页失败响应
     * @apiNote 仅捕获当前线程调用过程中同步抛出的 {@link Exception}。
     * 本方法不会订阅 {@code Publisher}，也不会捕获订阅或其他异步执行阶段的异常。
     * 响应式分页调用应在链路中使用 {@code map} 将查询结果转换为 PageView、
     * 使用 {@code onErrorResume} 包装失败结果；阻塞数据库调用的线程调度由调用侧负责。
     */
    public static <R, T> PageView<T> wrap(R req, Function<R, PageView<T>> function) {
        try {
            return function.apply(req);
        } catch (Exception e) {
            log.error("业务调用失败", e);
            return exception(e);
        }
    }

    /**
     * 执行双参数同步分页调用，直接返回业务分页结果，异常包装为分页失败响应。
     *
     * @param req1     第一个请求参数
     * @param req2     第二个请求参数
     * @param function 返回分页结果的同步业务函数
     * @param <T>      第一个请求参数类型
     * @param <U>      第二个请求参数类型
     * @param <R>      分页记录类型
     * @return 业务分页结果或分页失败响应
     * @apiNote 仅捕获当前线程调用过程中同步抛出的 {@link Exception}。
     * 本方法不会订阅 {@code Publisher}，也不会捕获订阅或其他异步执行阶段的异常。
     * 响应式分页调用应在链路中使用 {@code map} 将查询结果转换为 PageView、
     * 使用 {@code onErrorResume} 包装失败结果；阻塞数据库调用的线程调度由调用侧负责。
     */
    public static <T, U, R> PageView<R> wrap(T req1, U req2, BiFunction<T, U, PageView<R>> function) {
        try {
            return function.apply(req1, req2);
        } catch (Exception e) {
            log.error("业务调用失败", e);
            return exception(e);
        }
    }
}
