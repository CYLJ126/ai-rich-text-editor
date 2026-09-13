package com.arte.app.web.aspect;

import cn.hutool.core.text.CharSequenceUtil;
import cn.hutool.core.util.ClassUtil;
import cn.hutool.json.JSONUtil;
import com.arte.app.pojo.richtext.ArticleDto;
import com.arte.app.pojo.richtext.ArticleHistoryPo;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.exception.CommonException;
import com.arte.core.pojo.IResult;
import com.arte.core.pojo.PageView;
import com.arte.core.pojo.ResultContext;
import com.arte.core.pojo.UserContext;
import com.baomidou.mybatisplus.core.metadata.IPage;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Part;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.stream.Collectors;

/**
 * web 切面
 * web 请求进入顺序：filter -> interceptor -> controllerAdvice -> aspect -> controller，如果有在切面之前的处理，则应在更前面设置日志ID；
 *
 * @author zhangsc
 * @since 2025/1/2 15:05
 */
@Aspect
@Service
@Slf4j
public class WebAspect {

    @Around(value = "(execution(public * com.arte.app.web.controller..*.*(..))" +
            "&& !execution(* com.arte.app.web.interceptor..*.*(..)))")
    public Object deal(ProceedingJoinPoint pjp) {
        IResult<?> result = null;
        Logger logger = LoggerFactory.getLogger(pjp.getTarget().getClass());
        String methodName = pjp.getTarget().getClass().getSimpleName() + "#" + pjp.getSignature().getName();
        logger.info("方法：{}，请求参数摘要：{}", methodName, summarizeArguments(pjp.getArgs()));
        MethodSignature methodSignature = (MethodSignature) pjp.getSignature();
        Class<?> returnType = methodSignature.getMethod().getReturnType();
        long start = System.currentTimeMillis();
        try {
            if (!ClassUtil.isAssignable(returnType, IResult.class)) {
                Object realResult = pjp.proceed();
                logger.info("方法{}，消耗时间：{}ms，返回结果摘要：{}", methodName, System.currentTimeMillis() - start, summarizeResult(realResult));
                return realResult;
            }
            result = (ResultContext<?>) pjp.proceed();
            if (result.getSuccess() == null || result.getCode() == null) {
                throw new CommonException(ResultCodeEnum.UNKNOWN, "success|code返回结果有误！");
            }
            if (CharSequenceUtil.isBlank(result.getDesc())) {
                result.setDesc(ResultContext.BUSINESS_SUCCESS_LABEL);
            }
            return result;
        } catch (Throwable throwable) {
            logger.error("方法{}，出现异常", methodName, throwable);
            if (ClassUtil.isAssignable(returnType, ResultContext.class)) {
                if (throwable instanceof SQLException) {
                    result = ResultContext.fail(ResultCodeEnum.DB_EXCEPTION);
                } else {
                    result = ResultContext.exception(throwable);
                }
                return result;
            } else if (ClassUtil.isAssignable(returnType, PageView.class)) {
                if (throwable instanceof SQLException) {
                    result = PageView.fail(ResultCodeEnum.DB_EXCEPTION);
                } else {
                    result = PageView.exception(throwable);
                }
                return result;
            } else {
                throw new CommonException("error.common.exec", throwable);
            }
        } finally {
            //清空缓存
            if (result != null) {
                logger.info("方法{}，消耗时间：{}ms，返回结果摘要：{}", methodName, System.currentTimeMillis() - start, summarizeResult(result));
            }
            UserContext.clear();
            MDC.clear();
        }
    }

    private String summarizeArguments(Object[] args) {
        if (args == null || args.length == 0) {
            return CharSequenceUtil.EMPTY;
        }
        try {
            return Arrays.stream(args)
                    .map(this::summarizeValue)
                    .filter(CharSequenceUtil::isNotEmpty)
                    .collect(Collectors.joining(", ", "[", "]"));
        } catch (RuntimeException exception) {
            log.debug("请求参数无法生成日志摘要，已忽略，异常：{}: {}",
                    exception.getClass().getName(), exception.getMessage());
            return CharSequenceUtil.EMPTY;
        }
    }

    private String summarizeResult(Object result) {
        try {
            if (result instanceof IResult<?> iResult) {
                String dataSummary = summarizeValue(iResult.getData());
                return "{code=" + iResult.getCode()
                        + ", success=" + iResult.getSuccess()
                        + (CharSequenceUtil.isEmpty(dataSummary) ? "" : ", data=" + dataSummary) + "}";
            }
            if (result instanceof ResponseEntity<?> response) {
                String bodySummary = summarizeValue(response.getBody());
                return "ResponseEntity{status=" + response.getStatusCode()
                        + (CharSequenceUtil.isEmpty(bodySummary) ? "" : ", body=" + bodySummary) + "}";
            }
            return summarizeValue(result);
        } catch (RuntimeException exception) {
            log.debug("返回结果无法生成日志摘要，已忽略，类型：{}，异常：{}: {}",
                    className(result), exception.getClass().getName(), exception.getMessage());
            return CharSequenceUtil.EMPTY;
        }
    }

    private String summarizeValue(Object value) {
        if (value == null) {
            return CharSequenceUtil.EMPTY;
        }
        try {
            if (value instanceof MultipartFile file) {
                return "MultipartFile{name=" + file.getName()
                        + ", originalFilename=" + file.getOriginalFilename()
                        + ", contentType=" + file.getContentType()
                        + ", size=" + file.getSize() + "}";
            }
            if (shouldIgnore(value)) {
                return CharSequenceUtil.EMPTY;
            }
            if (value instanceof ArticleDto article) {
                return "ArticleDto{id=" + article.getId()
                        + ", contentJsonLength=" + length(article.getContentJson())
                        + ", contentMdLength=" + length(article.getContentMd())
                        + ", contentTextLength=" + length(article.getContentText()) + "}";
            }
            if (value instanceof ArticleHistoryPo history) {
                return "ArticleHistoryPo{id=" + history.getId()
                        + ", articleId=" + history.getArticleId()
                        + ", versionNo=" + history.getVersionNo()
                        + ", contentLength=" + length(history.getContent()) + "}";
            }
            if (value instanceof Collection<?> collection) {
                return value.getClass().getSimpleName() + "{size=" + collection.size() + "}";
            }
            if (value instanceof IPage<?> page) {
                return "Page{current=" + page.getCurrent()
                        + ", size=" + page.getSize()
                        + ", total=" + page.getTotal() + "}";
            }
            return JSONUtil.toJsonStr(value);
        } catch (RuntimeException exception) {
            log.debug("对象无法序列化为日志摘要，已忽略，类型：{}，异常：{}: {}",
                    className(value), exception.getClass().getName(), exception.getMessage());
            return CharSequenceUtil.EMPTY;
        }
    }

    private boolean shouldIgnore(Object value) {
        Class<?> valueType = value.getClass();
        return valueType == byte[].class
                || valueType == Byte[].class
                || value instanceof ByteBuffer
                || value instanceof InputStream
                || value instanceof OutputStream
                || value instanceof Reader
                || value instanceof Writer
                || value instanceof Resource
                || value instanceof ServletRequest
                || value instanceof ServletResponse
                || value instanceof Part
                || value instanceof ResponseBodyEmitter
                || value instanceof StreamingResponseBody;
    }

    private String className(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    private int length(String value) {
        return value == null ? 0 : value.length();
    }
}
