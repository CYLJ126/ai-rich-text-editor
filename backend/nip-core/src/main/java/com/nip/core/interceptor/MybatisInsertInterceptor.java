package com.nip.core.interceptor;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.nip.core.annotations.MybatisParams;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.insert.Insert;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Mybatis 插入拦截器
 *
 * @author zhangsc
 * @since 2025/12/20 23:26
 */
@Slf4j
public class MybatisInsertInterceptor extends MybatisInterceptor implements InnerInterceptor {

    private static final String INSERT_STATEMENT_TYPE = "INSERT";

    @Override
    public void beforeUpdate(Executor executor, MappedStatement ms, Object parameter) {
        if (SqlCommandType.INSERT != ms.getSqlCommandType()) {
            return;
        }
        statementType.set(INSERT_STATEMENT_TYPE);
        FieldAndValue[] fieldAndValues = fillFieldAndValues(ms, parameter);
        supplement.set(fieldAndValues);
    }

    @Override
    protected boolean checkStatementType(Statement statement) {
        return statement instanceof Insert;
    }

    @Override
    protected String[] getFieldsByStatementType(MybatisParams annotation) {
        return annotation.insertFields();
    }

    @Override
    protected void addFieldsByStatementType(Statement statement, FieldAndValue[] fieldAndValues) {
        Insert insertStatement = (Insert) statement;
        // 如果没有列定义，创建一个新的列表
        if (insertStatement.getColumns() == null) {
            insertStatement.setColumns(new ExpressionList<>());
        }
        // 获取已存在的字段，避免重复添加
        Set<String> existingColumns = new HashSet<>();
        for (Column column : insertStatement.getColumns()) {
            existingColumns.add(column.getColumnName().toLowerCase());
        }
        // 处理 INSERT INTO ... VALUES (...) 语句
        if (insertStatement.getValues() != null) {
            handleSimpleInsert(insertStatement, insertStatement.getColumns(), fieldAndValues, existingColumns);
        }
        // 处理 INSERT INTO ... SELECT ... 的情况
        else if (insertStatement.getSelect() != null) {
            log.warn("INSERT INTO ... SELECT 语句暂不支持自动添加字段，建议在 SELECT 子句中手动添加相关字段");
        }
        // 处理其他情况，比如批量插入等 TODO
        else {
            log.warn("暂不支持此类型的 INSERT 语句自动添加字段");
        }
    }

    @Override
    String getStatementType() {
        return INSERT_STATEMENT_TYPE;
    }

    /**
     * 处理简单的 INSERT 语句 (INSERT INTO table (col1, col2) VALUES (val1, val2))
     */
    private void handleSimpleInsert(Insert insertStatement, List<Column> columns,
                                    FieldAndValue[] fieldAndValues, Set<String> existingColumns) {

        ExpressionList values = insertStatement.getValues().getExpressions();
        if (CollUtil.isEmpty(values)) {
            return;
        }
        List<Expression> expressions = values.getExpressions();
        // 添加新字段
        for (FieldAndValue fieldAndValue : fieldAndValues) {
            String columnName = fieldAndValue.sqlName().toLowerCase();
            if (!existingColumns.contains(columnName)) {
                // 添加列
                columns.add(new Column(fieldAndValue.sqlName()));
                // 添加对应的值
                Expression valueExpression = valueExpression(fieldAndValue.value());
                expressions.add(valueExpression);
                existingColumns.add(columnName);
                log.debug("添加插入字段: {} = {}", fieldAndValue.sqlName(), fieldAndValue.value());
            } else {
                log.debug("字段 {} 已存在，跳过添加", fieldAndValue.sqlName());
            }
        }
    }
}