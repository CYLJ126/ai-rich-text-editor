package com.arte.core.interceptor;

import cn.hutool.core.date.DatePattern;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.inner.BaseMultiTableInnerInterceptor;
import com.arte.core.annotations.MybatisParams;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.exception.CommonException;
import com.arte.core.i18n.MessageUtils;
import com.arte.core.pojo.UserContext;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.*;
import java.util.Collection;
import java.util.Date;

/**
 * Mybatis 拦截器，对于注解了 {@link MybatisParams} 参数的实体类或 Mapper 接口或方法，会自动添加插入字段
 *
 * @author zhangsc
 * @since 2025/7/21 20:11
 */
@Slf4j
public abstract class MybatisInterceptor extends BaseMultiTableInnerInterceptor {

    /**
     * 字段名值
     *
     * @param tableName 表名
     * @param objName   Java 字段名
     * @param sqlName   sql 字段名
     * @param value     Java 对象值
     */
    public record FieldAndValue(String tableName, String objName, String sqlName, Object value) {
    }

    /**
     * 用于记录当前处理的 Sql 语句类型，判断是否该执行相关 SQL 处理方法，确保只会有一种类型的拦截器执行
     */
    protected static ThreadLocal<String> statementType = new ThreadLocal<>();

    /**
     * 需要添加的字段名和值列表
     */
    protected static ThreadLocal<FieldAndValue[]> supplement = new ThreadLocal<>();

    /**
     * 忽略拦截，在业务方法逻辑中，通过设置此线程变量为 true，则该方法将不执行拦截逻辑
     */
    protected static ThreadLocal<Boolean> ignore = new ThreadLocal<>();

    public static void ignore() {
        ignore.set(true);
    }

    /**
     * 是否处理该 Sql 语句
     *
     * @param statement Sql 语句
     * @return 是否处理该 Sql 语句
     */
    abstract boolean checkStatementType(Statement statement);

    /**
     * 根据注解获取需要添加的字段，不同的 Sql 语句类型返回不同的字段
     *
     * @param annotation 注解
     * @return 要添加的字段名数组
     */
    abstract String[] getFieldsByStatementType(MybatisParams annotation);

    /**
     * 向 Sql 语句添加字段
     *
     * @param statement      Sql 语句
     * @param fieldAndValues 要添加的字段名和值列表
     */
    abstract void addFieldsByStatementType(Statement statement, FieldAndValue[] fieldAndValues);

    abstract String getStatementType();

    @Override
    public Expression buildTableExpression(Table table, Expression where, String whereSegment) {
        throw new UnsupportedOperationException(MessageUtils.get("error.common.interceptorUnsupported"));
    }

    @Override
    public void beforePrepare(StatementHandler sh, Connection connection, Integer transactionTimeout) {
        try {
            FieldAndValue[] fieldAndValues = supplement.get();
            // 不是对应的 SQL 类型、字段名值列表为空、被线程忽略掉，均不处理
            if (!StrUtil.equals(getStatementType(), statementType.get()) || ArrayUtil.isEmpty(fieldAndValues) || BooleanUtil.isTrue(ignore.get())) {
                return;
            }
            // 获取 BoundSql
            BoundSql boundSql = sh.getBoundSql();
            String originalSql = boundSql.getSql();
            log.debug("原始 SQL: {}", originalSql);
            // 解析并修改 SQL
            Statement statement = CCJSqlParserUtil.parse(originalSql);
            if (checkStatementType(statement)) {
                addFieldsByStatementType(statement, fieldAndValues);
                String modifiedSql = statement.toString();
                log.debug("修改后 SQL: {}", modifiedSql);
                // 使用反射修改 BoundSql 中的 sql
                Field sqlField = BoundSql.class.getDeclaredField("sql");
                sqlField.setAccessible(true);
                sqlField.set(boundSql, modifiedSql);
            }
        } catch (Exception e) {
            log.error("SQL 修改失败，按原语句执行", e);
        } finally {
            // 执行 UPDATE 语句时，会先进一次 INSERT，再进 UPDATE，这里避免进 INSERT 时把参数清空掉
            if (StrUtil.equals(getStatementType(), statementType.get())) {
                supplement.remove();
                ignore.remove();
                statementType.remove();
            }
        }
    }

    /**
     * 检查是否有 {@link MybatisParams} 注解，并返回注解
     *
     * @param ms        MappedStatement
     * @param parameter 请求参数
     * @return {@link MybatisParams} 注解
     */
    protected MybatisParams checkAndGetAnnotation(MappedStatement ms, Object parameter) {
        if (BooleanUtil.isTrue(ignore.get())) {
            log.debug("{} 数据处理通过 ThreadLocal 被设置为忽略，不做拦截", ms.getId());
            return null;
        }
        // 先获取实体类上的注解
        MybatisParams annotation = getAnnotation(parameter);
        if (annotation == null) {
            // 其次 Mapper 中的注解
            annotation = getAnnotation(ms);
        }
        if (annotation == null || annotation.ignore()) {
            log.debug("{} 数据处理参数注解不存在，或注解被设置为忽略，不做拦截", ms.getId());
            return null;
        }
        return annotation;
    }

    /**
     * 准备需要添加的字段和值
     *
     * @param ms        MappedStatement
     * @param parameter 参数
     */
    protected FieldAndValue[] fillFieldAndValues(MappedStatement ms, Object parameter) {
        MybatisParams annotation = checkAndGetAnnotation(ms, parameter);
        if (annotation == null) {
            return null;
        }
        FieldAndValue[] fieldAndValues = getValues(annotation.value(), getFieldsByStatementType(annotation));
        if (fieldAndValues.length == 0) {
            log.debug("{} 没有需要添加的更新字段", ms.getId());
            return null;
        }
        log.info("{} 新增更新值：{}", ms.getId(), printParams(fieldAndValues));
        return fieldAndValues;
    }

    /**
     * 根据 MappedStatement 获取 mapper 方法注解
     *
     * @param mappedStatement mappedStatement 对象
     * @return MybatisParams 注解
     */
    protected MybatisParams getAnnotation(MappedStatement mappedStatement) {
        String mapperClassName = mappedStatement.getId().substring(0, mappedStatement.getId().lastIndexOf("."));
        String methodName = mappedStatement.getId().substring(mappedStatement.getId().lastIndexOf(".") + 1);
        try {
            // 加载Mapper接口类
            Class<?> mapperClass = Class.forName(mapperClassName);
            // 获取Mapper接口方法
            Method[] methods = mapperClass.getMethods();
            Method targetMethod = null;
            for (Method method : methods) {
                if (method.getName().equals(methodName)) {
                    targetMethod = method;
                    break;
                }
            }
            // 1. 优先获取方法上的 @MybatisParams 注解
            if (targetMethod != null) {
                MybatisParams annotation = targetMethod.getAnnotation(MybatisParams.class);
                if (annotation != null) {
                    return annotation;
                }
            }
            // 2. 其次获取 Mapper 接口上的 @MybatisParams 注解
            return mapperClass.getAnnotation(MybatisParams.class);
        } catch (ClassNotFoundException e) {
            throw new CommonException(ResultCodeEnum.SYSTEM_EXCEPTION, mappedStatement.getId() + "方法未找到");
        }
    }

    /**
     * 获取实体类上的 {@link MybatisParams} 注解
     *
     * @param parameter 实体类请求参数
     * @return {@link MybatisParams} 注解
     */
    protected MybatisParams getAnnotation(Object parameter) {
        if (parameter == null) {
            return null;
        }
        Class<?> aClass = parameter.getClass();
        if (!StrUtil.startWith(aClass.getPackageName(), "com.arte")) {
            // 只拦截本项目功能
            return null;
        }
        while (aClass != null) {
            MybatisParams annotation = aClass.getAnnotation(MybatisParams.class);
            if (annotation != null) {
                return annotation;
            } else {
                aClass = aClass.getSuperclass();
            }
        }
        return null;
    }

    /**
     * 获取字段名与字段值
     *
     * @param tableName 表名
     * @param fields    要处理的字段列表
     * @return FieldAndValue 列表
     */
    protected FieldAndValue[] getValues(String tableName, String[] fields) {
        FieldAndValue[] values = new FieldAndValue[fields.length];
        for (int i = 0; i < fields.length; i++) {
            values[i] = new FieldAndValue(tableName, fields[i], camelToSnake(fields[i]), getValue(fields[i]));
        }
        return values;
    }

    protected Object getValue(String fieldName) {
        return switch (fieldName) {
            case MybatisParams.CREATE_BY, MybatisParams.UPDATE_BY ->
                    UserContext.hasUserOnlineInfo() ? UserContext.getUserOnlineInfo().getUserName() : StrUtil.EMPTY;
            case MybatisParams.CREATE_TIME, MybatisParams.UPDATE_TIME -> LocalDateTime.now();
            default -> null;
        };
    }

    /**
     * 驼峰转下划线
     *
     * @param camelCase 驼峰命名字符串
     * @return 下划线命名字符串
     */
    public static String camelToSnake(String camelCase) {
        if (camelCase == null || camelCase.isEmpty()) {
            return camelCase;
        }
        // 使用正则表达式匹配大写字母并在前面加下划线
        String snakeCase = camelCase.replaceAll("([A-Z])", "_$1").toLowerCase();
        // 处理开头可能多出的下划线
        if (snakeCase.startsWith("_")) {
            snakeCase = snakeCase.substring(1);
        }
        return snakeCase;
    }

    public static String printParams(FieldAndValue[] params) {
        StringBuilder sb = new StringBuilder();
        for (FieldAndValue param : params) {
            sb.append(String.format("java 字段[%s]，sql 字段[%s]，值[%s]；\n", param.objName, param.sqlName(), param.value()));
        }
        return sb.toString();
    }

    public static Expression and(Collection<Expression> expressions) {
        if (expressions.isEmpty()) {
            return null;
        }
        return expressions.stream().reduce(AndExpression::new).get();
    }

    public static Expression or(Collection<Expression> expressions) {
        if (expressions.isEmpty()) {
            return null;
        }
        return expressions.stream().reduce(OrExpression::new).get();
    }

    public static Expression createIsNullExpression(String columnName) {
        Column column = new Column(columnName);
        return new IsNullExpression().withLeftExpression(column);
    }

    public static Expression createBetweenExpression(String columnName, Object start, Object end) {
        Column column = new Column(columnName);
        Expression startExpr = valueExpression(start);
        Expression endExpr = valueExpression(end);

        Between between = new Between();
        between.setLeftExpression(column);
        between.setBetweenExpressionStart(startExpr);
        between.setBetweenExpressionEnd(endExpr);
        return between;
    }

    public static Expression createInExpression(String columnName, Object... values) {
        Column column = new Column(columnName);
        ExpressionList<Expression> exprList = new ExpressionList<>();

        for (Object value : values) {
            exprList.addExpressions(valueExpression(value));
        }

        InExpression inExpr = new InExpression();
        inExpr.setLeftExpression(column);
        inExpr.setRightExpression(exprList);
        return inExpr;
    }

    protected static Expression valueExpression(Object value) {
        Expression expr;
        switch (value) {
            case String s -> expr = new StringValue(s);
            case Integer i -> expr = new LongValue(i.toString());
            case Long l -> expr = new LongValue(l.toString());
            case Number number -> expr = new DoubleValue(number.toString());
            case Boolean b -> expr = new BooleanValue(b);
            case Timestamp timestamp -> expr = new TimestampValue(timestamp.toString());
            case java.sql.Date date -> expr = new DateValue(date);
            case Date date -> expr = new TimestampValue(new Timestamp(date.getTime()).toString());
            case LocalDate date -> expr = new StringValue(date.format(DatePattern.NORM_DATE_FORMATTER));
            case LocalDateTime dateTime -> expr = new StringValue(dateTime.format(DatePattern.NORM_DATETIME_FORMATTER));
            case Instant instant -> {
                LocalDateTime ldt = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
                String formattedDateTime = ldt.format(DatePattern.NORM_DATETIME_FORMATTER);
                expr = new StringValue(formattedDateTime);
            }
            case ZonedDateTime zonedDateTime -> {
                LocalDateTime ldt = zonedDateTime.toLocalDateTime();
                String formattedDateTime = ldt.format(DatePattern.NORM_DATETIME_FORMATTER);
                expr = new StringValue(formattedDateTime);
            }
            default -> expr = new IsNullExpression();
        }
        return expr;
    }
}
