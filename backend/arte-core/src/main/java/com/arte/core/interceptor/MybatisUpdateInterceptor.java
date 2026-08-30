package com.arte.core.interceptor;

import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.arte.core.annotations.MybatisParams;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Mybatis 更新拦截器
 *
 * @author zhangsc
 * @since 2025/7/21 19:26
 */
@Slf4j
public class MybatisUpdateInterceptor extends MybatisInterceptor implements InnerInterceptor {

    private static final String UPDATE_STATEMENT_TYPE = "UPDATE";

    @Override
    public void beforeUpdate(Executor executor, MappedStatement ms, Object parameter) {
        if (SqlCommandType.UPDATE != ms.getSqlCommandType()) {
            return;
        }
        statementType.set(UPDATE_STATEMENT_TYPE);
        FieldAndValue[] fieldAndValues = fillFieldAndValues(ms, parameter);
        supplement.set(fieldAndValues);
    }

    @Override
    protected boolean checkStatementType(Statement statement) {
        return statement instanceof Update;
    }

    @Override
    protected String[] getFieldsByStatementType(MybatisParams annotation) {
        return annotation.updateFields();
    }

    @Override
    protected void addFieldsByStatementType(Statement statement, FieldAndValue[] fieldAndValues) {
        Update updateStatement = (Update) statement;
        List<UpdateSet> updateSets = updateStatement.getUpdateSets();
        // 获取已存在的更新字段，避免重复添加
        Set<String> existingColumns = new HashSet<>();
        for (UpdateSet updateSet : updateSets) {
            for (Column column : updateSet.getColumns()) {
                existingColumns.add(column.getColumnName().toLowerCase());
            }
        }
        for (FieldAndValue fieldAndValue : fieldAndValues) {
            String columnName = fieldAndValue.sqlName().toLowerCase();
            // 检查字段是否已存在，避免重复添加
            if (!existingColumns.contains(columnName)) {
                UpdateSet newUpdateSet = new UpdateSet();
                Column column = new Column(fieldAndValue.sqlName());
                Expression valueExpression = valueExpression(fieldAndValue.value());
                newUpdateSet.add(column, valueExpression);
                updateSets.add(newUpdateSet);
                log.debug("添加更新字段: {} = {}", fieldAndValue.sqlName(), fieldAndValue.value());
            }
        }
    }

    @Override
    String getStatementType() {
        return UPDATE_STATEMENT_TYPE;
    }
}
