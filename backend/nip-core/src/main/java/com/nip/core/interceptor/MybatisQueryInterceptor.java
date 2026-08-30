package com.nip.core.interceptor;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.PluginUtils;
import com.baomidou.mybatisplus.extension.parser.JsqlParserGlobal;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.nip.core.annotations.MybatisParams;
import com.nip.core.enums.ResultCodeEnum;
import com.nip.core.exception.CommonException;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Mybatis 查询拦截器
 *
 * @author zhangsc
 * @since 2025/7/21 19:26
 */
@Slf4j
public class MybatisQueryInterceptor extends MybatisInterceptor implements InnerInterceptor {

    private static final String SELECT_STATEMENT_TYPE = "SELECT";

    /**
     * 主要是处理 boundSql，替换 sql 语句，并同时增加字段值映射
     *
     * @param executor      Mybatis 执行器
     * @param ms            MappedStatement
     * @param parameter     sql 参数
     * @param rowBounds     rowBounds
     * @param resultHandler resultHandler
     * @param boundSql      boundSql
     */
    @Override
    public void beforeQuery(Executor executor, MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) {
        try {
            FieldAndValue[] fieldAndValues = fillFieldAndValues(ms, parameter);
            if (BooleanUtil.isTrue(ignore.get()) || fieldAndValues == null || fieldAndValues.length == 0) {
                return;
            }
            supplement.set(fieldAndValues);
            Statement rawStatement = JsqlParserGlobal.parse(boundSql.getSql());
            if (!(rawStatement instanceof PlainSelect statement)) {
                return;
            }
            // 这里会调用下面的 buildTableExpression 方法
            processPlainSelect(statement, "");
            PluginUtils.MPBoundSql mpBoundSql = PluginUtils.mpBoundSql(boundSql);
            Map<String, Object> additionalParameters = mpBoundSql.additionalParameters();
            List<ParameterMapping> parameterMappings = mpBoundSql.parameterMappings();
            for (FieldAndValue fieldAndValue : fieldAndValues) {
                // 添加条件值
                additionalParameters.put(fieldAndValue.sqlName(), fieldAndValue.value());
                // 添加条件参数映射
                parameterMappings.add(new ParameterMapping.Builder(
                        ms.getConfiguration(),
                        fieldAndValue.sqlName(),
                        fieldAndValue.value().getClass()
                ).build());
            }
            mpBoundSql.parameterMappings(parameterMappings);
            // 构造新的 sql
            mpBoundSql.sql(statement.toString());
        } catch (JSQLParserException e) {
            throw new CommonException(ResultCodeEnum.SYSTEM_EXCEPTION, ms.getId() + " 添加额外查询条件出错", e);
        } finally {
            supplement.remove();
            ignore.remove();
        }
    }

    @Override
    public void beforePrepare(StatementHandler sh, Connection connection, Integer transactionTimeout) {
        // 覆盖父方法，什么也不做
    }

    /**
     * 构造额外的 where 条件，会追加到原 where 条件中
     *
     * @param table        表对象
     * @param where        当前where条件
     * @param whereSegment 所属Mapper对象全路径
     * @return 额外的 where 条件表达式
     */
    @Override
    public Expression buildTableExpression(Table table, Expression where, String whereSegment) {
        FieldAndValue[] fieldAndValues = supplement.get();
        if (fieldAndValues == null || !StrUtil.equalsIgnoreCase(table.getName(), fieldAndValues[0].tableName())) {
            // 非空校验；
            // 表名校验，联表查询时，非 Mapper 接口对应的表，不做处理；目前仅支持单表，若需要支持多表，需要调整 MybatisParams 注解参数，说明哪个字段是哪个表的；
            return null;
        }
        Set<Expression> expressions = new java.util.HashSet<>();
        for (FieldAndValue condition : fieldAndValues) {
            if (StrUtil.isNotBlank(condition.sqlName()) && Objects.nonNull(condition.value())) {
                EqualsTo equalsTo = new EqualsTo();
                String columnName = Objects.isNull(table.getAlias()) ? condition.sqlName() : table.getAlias().getName() + "." + condition.sqlName();
                equalsTo.setLeftExpression(new Column(columnName));
                // ? 占位符
                equalsTo.setRightExpression(new JdbcParameter());
                expressions.add(equalsTo);
            }
        }
        return and(expressions);
    }

    @Override
    String[] getFieldsByStatementType(MybatisParams annotation) {
        return annotation.queryFields();
    }

    @Override
    void addFieldsByStatementType(Statement statement, FieldAndValue[] fieldAndValues) {
    }

    @Override
    String getStatementType() {
        return SELECT_STATEMENT_TYPE;
    }

    @Override
    boolean checkStatementType(Statement statement) {
        return statement instanceof Select;
    }
}
