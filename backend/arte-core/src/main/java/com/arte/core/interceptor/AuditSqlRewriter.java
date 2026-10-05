package com.arte.core.interceptor;

import com.arte.core.annotations.MybatisParams;
import com.arte.core.pojo.UserContext;
import com.baomidou.mybatisplus.core.toolkit.PluginUtils;
import com.baomidou.mybatisplus.extension.plugins.inner.BaseMultiTableInnerInterceptor;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.AnyComparisonExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlCommandType;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 旧业务审计字段与行过滤条件的 SQL 改写器。
 * <p>
 * 由 MyBatis 拦截器对需要处理的 BoundSql 创建独立实例，不执行 JDBC，也不创建 PreparedStatement。
 * 审计数据始终作为 JDBC 参数绑定；按最终 SQL 的占位符顺序重建映射。
 * AST 序列化可能规范化 SQL 格式或省略原注释，不保证原 SQL 文本逐字保留。
 * 旧用户身份从实际执行线程的 UserContext 读取；异步调用须在该线程建立并关闭受信身份作用域。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 23:30 ✾
 */
final class AuditSqlRewriter extends BaseMultiTableInnerInterceptor {
    /**
     * SQL 字面量／标识符／注释优先匹配，避免将其中的 ?、:name 当成参数。
     * <p>
     * 此扫描器仅用于参数标记替换；SQL 语法解析由 JSQLParser 完成。
     * <ul>
     * <li>字符串、带引号的标识符和注释。</li>
     * <li>真正的 ? 参数占位符。</li>
     * <li>:name 形式的命名标记。</li>
     * </ul>
     */
    private static final Pattern TOKENS = Pattern.compile(
            "'(?:''|\\\\.|[^'\\\\])*'|\"(?:\"\"|\\\\.|[^\"\\\\])*\"|`(?:``|[^`])*`|/\\*[\\s\\S]*?\\*/|--[^\\r\\n]*|#[^\\r\\n]*|\\?|:[A-Za-z_][A-Za-z_0-9]*");
    private static final String PREFIX = "_arte_audit_";
    private final MappedStatement mappedStatement;
    private final MybatisParams annotation;
    private final Map<String, Object> values = new LinkedHashMap<>();
    private final Set<Select> visited = Collections.newSetFromMap(new IdentityHashMap<>());

    private AuditSqlRewriter(MappedStatement mappedStatement, MybatisParams annotation) {
        this.mappedStatement = mappedStatement;
        this.annotation = annotation;
    }

    static void rewrite(MappedStatement mapped, BoundSql bound, MybatisParams annotation) {
        new AuditSqlRewriter(mapped, annotation).rewrite(bound);
    }

    /**
     * 修改 SQL 的同时，按修改后的 ? 顺序重新排列参数映射，保证每个占位符绑定正确的值。
     * 标记原占位符 → 用 AST 改写 SQL → 恢复占位符并重建参数映射
     *
     * @param bound 待改写 SQL 的 BoundSql
     */
    private void rewrite(BoundSql bound) {
        String[] audit = switch (mappedStatement.getSqlCommandType()) {
            case INSERT -> annotation.insertFields();
            case UPDATE -> annotation.updateFields();
            default -> new String[0];
        };
        // INSERT 无插入审计字段时跳过；其它语句既无填充字段也无行过滤条件时跳过。
        if (audit.length == 0 && (mappedStatement.getSqlCommandType() == SqlCommandType.INSERT || annotation.queryFields().length == 0))
            return;
        // 审计表名不能为空
        if (annotation.value().isBlank()) {
            throw new IllegalStateException("Missing audit table: " + mappedStatement.getId());
        }
        // 保留原 ParameterMapping，包括 TypeHandler、JdbcType 和 foreach 属性。
        List<ParameterMapping> originals = bound.getParameterMappings() == null
                ? mappedStatement.getParameterMap().getParameterMappings() : bound.getParameterMappings();
        if (originals.stream().anyMatch(mapping -> mapping.getProperty().startsWith(PREFIX))) {
            // 确保没有参数属性与审计参数属性冲突，用户不得以 _arte_audit_ 开头命名参数，如 @Param("_arte_audit_value_0")
            throw new IllegalStateException("Reserved audit parameter property: " + mappedStatement.getId());
        }
        // 创建扫描器（还没有进行查找）
        var originalTokens = TOKENS.matcher(bound.getSql());
        var marked = new StringBuilder();
        int originalIndex = 0;
        // 将原占位符替换为 :_arte_audit_original_0、:_arte_audit_original_1 等，编号用于记住它们
        // 分别对应原来的哪条 ParameterMapping，之后即使新增条件插到了中间，也不会丢失这种对应关系。
        while (originalTokens.find()) {
            String token = originalTokens.group();
            if (token.startsWith(":"))
                throw new IllegalStateException("Raw named JDBC parameter is unsupported: " + mappedStatement.getId());
            originalTokens.appendReplacement(marked, Matcher.quoteReplacement(
                    token.equals("?") ? ":" + PREFIX + "original_" + originalIndex++ : token));
        }
        // 补上最后一次 token 匹配之后的文本；最后一个 token 也可能是字符串或注释，并不一定是 ?。
        originalTokens.appendTail(marked);
        if (originalIndex != originals.size()) {
            // 原占位符数量与 ParameterMapping 数量不一致
            throw new IllegalStateException("SQL parameter count mismatch: " + mappedStatement.getId());
        }
        try {
            // 解析成尚未改写的 AST 列表；支持项目中分号分隔的旧批量 UPDATE。
            var statements = CCJSqlParserUtil.parseStatements(marked.toString()).getStatements();
            if (statements.isEmpty()) {
                throw new IllegalStateException("Empty audited SQL");
            }
            for (Statement statement : statements) {
                switch (statement) {
                    case Select select -> processSelectBody(select, "");
                    case Insert insert -> insert(insert);
                    case Update update -> update(update);
                    case Delete delete -> delete(delete);
                    default -> throw new IllegalStateException("Unsupported audited SQL: " + mappedStatement.getId());
                }
            }
            // 将 AST 转回 SQL，此时 statements 保存的是已经修改过的 SQL 语法树对象
            String rendered = statements.stream().map(Object::toString).collect(Collectors.joining("; "));
            var finalTokens = TOKENS.matcher(rendered);
            var sql = new StringBuilder();
            var mappings = new ArrayList<ParameterMapping>();
            // 从左到右扫描改写后的 SQL，同步生成最终 SQL 和最终参数映射列表
            while (finalTokens.find()) {
                String token = finalTokens.group();
                if (token.startsWith(":" + PREFIX)) {
                    String name = token.substring(1);
                    if (name.startsWith(PREFIX + "original_")) {
                        // 遇到 :_arte_audit_original_N，即原始 SQL 的占位符，取出原来的第 N 条 ParameterMapping，保留其 TypeHandler、JdbcType 等信息
                        mappings.add(originals.get(Integer.parseInt(name.substring((PREFIX + "original_").length()))));
                    } else {
                        if (!values.containsKey(name) || bound.hasAdditionalParameter(name)) {
                            throw new IllegalStateException("Audit parameter collision: " + mappedStatement.getId());
                        }
                        Object value = values.get(name);
                        // 遇到 :_arte_audit_value_N，即拦截器要添加的额外参数，从 values 中取得审计值，新建参数映射，并将值放进 BoundSql 的附加参数
                        mappings.add(new ParameterMapping.Builder(mappedStatement.getConfiguration(), name, value.getClass()).build());
                        bound.setAdditionalParameter(name, value);
                    }
                    token = "?";
                } else if (token.equals("?") || token.startsWith(":")) {
                    // 遇到无法对应的参数标记，报错，避免执行错误绑定的 SQL
                    throw new IllegalStateException("Unmapped SQL parameter: " + mappedStatement.getId());
                }
                // 内部标记恢复为 ?，其它 token 原样保留；同时复制两次匹配之间的 SQL 文本。
                finalTokens.appendReplacement(sql, Matcher.quoteReplacement(token));
            }
            finalTokens.appendTail(sql);
            // 将 SQL 和新的映射列表写回 BoundSql
            var target = PluginUtils.mpBoundSql(bound);
            target.sql(sql.toString());
            target.parameterMappings(mappings);
        } catch (JSQLParserException e) {
            // 必须失败，不得降级执行未经审计或未经创建人约束的原语句。
            throw new IllegalStateException("Cannot rewrite audited SQL: " + mappedStatement.getId(), e);
        }
    }

    /**
     * 获取创建人／更新人或时间审计值，保存到本次改写的参数集合，返回临时命名参数表达式。
     * 实际值不会拼进 SQL；rewrite 会将标记转换为 ? 并加入 BoundSql 的附加参数。
     *
     * @param field MybatisParams 定义的 Java 字段名
     * @return 待恢复为 JDBC 占位符的命名参数表达式
     * @throws IllegalStateException 当前执行线程缺少受信用户名，或字段不受支持
     */
    private Expression parameter(String field) {
        Object value = switch (field) {
            case MybatisParams.CREATE_BY, MybatisParams.UPDATE_BY -> {
                // ThreadLocal 不自动跨线程传播；旧异步服务须在阻塞回调线程内打开并关闭身份作用域。
                String actor = UserContext.hasUserOnlineInfo() ? UserContext.getUserName() : null;
                if (actor == null || actor.isBlank()) {
                    throw new IllegalStateException("Missing trusted audit identity: " + mappedStatement.getId());
                }
                yield actor;
            }
            case MybatisParams.CREATE_TIME, MybatisParams.UPDATE_TIME -> LocalDateTime.now();
            default -> throw new IllegalStateException("Unsupported audit field: " + field);
        };
        // 用 size() 做编号，即递增，有顺序
        String name = PREFIX + "value_" + values.size();
        values.put(name, value);
        return new JdbcNamedParameter(name);
    }

    /**
     * 判断表名是否与注解指定的表名匹配
     *
     * @param table 表对象
     * @return 是否匹配
     */
    private boolean matches(Table table) {
        String name = table.getName().replace("`", "").replace("\"", "");
        return name.equalsIgnoreCase(annotation.value());
    }

    /**
     * 为匹配注解的表构造 SELECT／UPDATE／DELETE 共用的行过滤表达式。
     * 此方法只返回额外条件，与原 WHERE／JOIN ON 的合并由父类调用方负责。
     *
     * @param table   目标表及别名
     * @param where   父类传入的原条件，本实现不直接使用
     * @param segment 父类传递的处理上下文字符串，本实现不使用，也不要求是 Mapper 路径
     * @return queryFields 各字段等值条件的 AND 组合；表不匹配或字段为空时返回 null
     */
    @Override
    public Expression buildTableExpression(Table table, Expression where, String segment) {
        if (!matches(table)) return null;
        Expression result = null;
        for (String field : annotation.queryFields()) {
            var column = new Column(table.getAlias() == null ? MybatisInterceptor.camelToSnake(field)
                    : table.getAlias().getName() + "." + MybatisInterceptor.camelToSnake(field));
            // 为新增条件创建 :_arte_audit_value_N 标记，后续按最终出现位置绑定审计值。
            var equals = new EqualsTo(column, parameter(field));
            result = result == null ? equals : new AndExpression(result, equals);
        }
        return result;
    }

    /**
     * 处理查询节点及其 CTE，再委托父类遍历普通查询、括号查询和集合查询。
     *
     * @param select  查询 AST，可为 null
     * @param segment 传给父类和子查询处理逻辑的上下文字符串
     * @throws IllegalStateException 查询类型未被当前行过滤策略支持
     */
    @Override
    protected void processSelectBody(Select select, String segment) {
        // 同一个 AST 对象可能从多个表达式遍历入口到达，只处理一次，避免重复追加条件和参数。
        if (select == null || !visited.add(select)) return;
        // WITH 定义中的查询也可能读取受限制的表，不能只处理外层 SELECT。
        processWithItems(select.getWithItemsList());
        if (!(select instanceof PlainSelect || select instanceof ParenthesedSelect || select instanceof SetOperationList)) {
            throw new IllegalStateException("Unsupported audited SELECT: " + mappedStatement.getId());
        }
        // 父类递归分派 PlainSelect、ParenthesedSelect 和 UNION 等 SetOperationList。
        super.processSelectBody(select, segment);
    }

    /**
     * 遍历 WITH／WITH RECURSIVE 定义中的 SELECT，应用与外层一致的行过滤策略。
     *
     * @param items CTE 定义列表，可为 null
     * @throws IllegalStateException CTE 内是 INSERT／UPDATE／DELETE 等非 SELECT 操作
     */
    private void processWithItems(List<WithItem<?>> items) {
        if (items == null) return;
        for (var item : items) {
            // 当前实现只支持查询型 CTE，不能把修改数据的 CTE 当作普通查询静默跳过。
            // getSelect() 本身会强制转换，须先检查真实节点类型，避免非 SELECT CTE 触发 ClassCastException。
            if (!(item.getParenthesedStatement() instanceof ParenthesedSelect select)) {
                throw new IllegalStateException("Unsupported data-modifying CTE");
            }
            processSelectBody(select, "");
        }
    }

    /**
     * 遍历表达式内部的子查询；用于 WHERE，也用于 SELECT 项、SET、HAVING、排序等表达式。
     *
     * @param expression 待遍历表达式，可为 null
     * @param segment    继续传给子查询处理逻辑的上下文字符串
     */
    @Override
    protected void processWhereSubSelect(Expression expression, String segment) {
        if (expression == null) return;
        // 使用表达式访问器递归进入 CASE、函数、EXISTS、IN 等表达式中的查询节点。
        expression.accept(new ExpressionVisitorAdapter<Void>() {
            /** 将表达式中的 SELECT 交回统一查询处理入口，不累积访问器返回值。 */
            @Override
            public <S> Void visit(Select select, S context) {
                processSelectBody(select, segment);
                return null;
            }

            /** 显式处理 ANY／ALL 比较中的 SELECT，避免默认访问器漏掉这个入口。 */
            @Override
            public <S> Void visit(AnyComparisonExpression comparison, S context) {
                processSelectBody(comparison.getSelect(), segment);
                return null;
            }
        }, null);
    }

    /**
     * 处理 SELECT 列表项内部的子查询，包括标量查询、CASE 和函数中的查询。
     *
     * @param item    SELECT 列表项
     * @param segment 传给表达式遍历逻辑的上下文字符串
     */
    @Override
    protected void processSelectItem(SelectItem item, String segment) {
        // 复用完整的表达式遍历，而不只判断列表项是否直接是 SELECT。
        processWhereSubSelect(item.getExpression(), segment);
    }

    /**
     * 处理普通 SELECT，补充遍历父类未完整覆盖的 HAVING、排序、分组和 JOIN ON 表达式。
     *
     * @param select  普通查询 AST
     * @param segment 传给父类及表达式遍历逻辑的上下文字符串
     */
    @SuppressWarnings({"unchecked"})
    @Override
    protected void processPlainSelect(PlainSelect select, String segment) {
        // 父类处理 SELECT 项、WHERE、FROM 和 JOIN，并合并目标表的行过滤条件。
        super.processPlainSelect(select, segment);
        // 其它子句也可包含子查询；统一入口的 visited 集合会防止已遍历的查询重复改写。
        processWhereSubSelect(select.getHaving(), segment);
        if (select.getOrderByElements() != null) {
            select.getOrderByElements().forEach(order -> processWhereSubSelect(order.getExpression(), segment));
        }
        if (select.getGroupBy() != null && select.getGroupBy().getGroupByExpressionList() != null) {
            select.getGroupBy().getGroupByExpressionList().forEach(expression -> processWhereSubSelect((Expression) expression, segment));
        }
        if (select.getJoins() != null) {
            for (var join : select.getJoins()) {
                if (join.getOnExpressions() != null)
                    join.getOnExpressions().forEach(expression -> processWhereSubSelect(expression, segment));
            }
        }
    }

    /**
     * 为带显式列名的单行／多行 VALUES INSERT 补齐或覆盖 insertFields 中的审计值。
     *
     * @param insert 待原地改写的 INSERT AST
     * @throws IllegalStateException 表名不符、行列数量不一致，或使用了未支持的 INSERT 形式
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void insert(Insert insert) {
        // 写入必须命中声明的表，配置错误时拒绝执行，不能漏掉必需的审计字段。
        if (!matches(insert.getTable()))
            throw new IllegalStateException("Audited INSERT table mismatch: " + mappedStatement.getId());
        // INSERT SELECT、隐式列顺序和 upsert 需要独立的审计／授权策略，当前通用实现不放行。
        if (insert.getValues() == null || insert.getColumns() == null || insert.getColumns().isEmpty()
                || nonEmpty(insert.getDuplicateUpdateSets()) || insert.getConflictAction() != null) {
            throw new IllegalStateException("Audited INSERT requires explicit columns and VALUES; upsert/SELECT must have an explicit policy");
        }
        // 单行 VALUES 是一个括号表达式列表；多行 VALUES 是包含多个括号列表的外层列表。
        ExpressionList expressions = insert.getValues().getExpressions();
        List<ExpressionList> rows;
        if (expressions instanceof ParenthesedExpressionList) rows = List.of(expressions);
        else {
            rows = new ArrayList<>();
            for (Object expression : expressions) {
                if (!(expression instanceof ParenthesedExpressionList row))
                    throw new IllegalStateException("Unsupported VALUES row");
                rows.add(row);
            }
        }
        // 先确认原始每一行与列清单对应，再统一追加审计列，避免生成错位的 VALUES。
        int width = insert.getColumns().size();
        if (rows.isEmpty() || rows.stream().anyMatch(row -> row.size() != width))
            throw new IllegalStateException("INSERT row width mismatch");
        for (String field : annotation.insertFields()) {
            String name = MybatisInterceptor.camelToSnake(field);
            int index = -1;
            // 找出已有审计列的位置；忽略大小写和 MySQL 标识符反引号。
            for (int i = 0; i < insert.getColumns().size(); i++) {
                if (insert.getColumns().get(i).getColumnName().replace("`", "").equalsIgnoreCase(name)) index = i;
            }
            if (index < 0) {
                // 列清单只追加一次，但每一行都必须在对应位置追加绑定参数。
                insert.getColumns().add(new Column(name));
                for (ExpressionList row : rows) row.add(parameter(field));
            } else {
                // 审计值由可信上下文提供，即使调用参数已带同名字段也覆盖。
                for (ExpressionList row : rows) row.set(index, parameter(field));
            }
        }
    }

    /**
     * 判断可空集合是否含元素，用于检查 JOIN、USING 和冲突更新等可选 AST 结构。
     *
     * @param items 可空集合
     * @return 集合存在且不为空时返回 true
     */
    private static boolean nonEmpty(Collection<?> items) {
        return items != null && !items.isEmpty();
    }

    /**
     * 为单表 UPDATE 添加 queryFields 行过滤条件，并补齐或覆盖 updateFields 的 SET 审计值。
     *
     * @param update 待原地改写的 UPDATE AST
     * @throws IllegalStateException 目标表不符，或使用需要专门策略的多表更新
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void update(Update update) {
        // 拒绝目标表配置不符及多表更新，避免审计列或行过滤条件作用到错误的表。
        if (!matches(update.getTable()))
            throw new IllegalStateException("Audited UPDATE table mismatch: " + mappedStatement.getId());
        if (update.getFromItem() != null || nonEmpty(update.getJoins()) || nonEmpty(update.getStartJoins())) {
            throw new IllegalStateException("Audited multi-table UPDATE requires an explicit policy");
        }
        // CTE、SET 值和 WHERE 中的子查询同样需要应用读取行过滤条件。
        processWithItems(update.getWithItemsList());
        for (var set : update.getUpdateSets()) {
            set.getValues().forEach(expression -> processWhereSubSelect(expression, ""));
        }
        processWhereSubSelect(update.getWhere(), "");
        // 父类将额外条件与原 WHERE 合并；原条件是 OR 时会加括号，保留其逻辑优先级。
        update.setWhere(andExpression(update.getTable(), update.getWhere(), ""));
        for (String field : annotation.updateFields()) {
            String name = MybatisInterceptor.camelToSnake(field);
            boolean found = false;
            for (UpdateSet set : update.getUpdateSets()) {
                for (int i = 0; i < set.getColumns().size(); i++) {
                    if (set.getColumns().get(i).getColumnName().replace("`", "").equalsIgnoreCase(name)) {
                        // 原 SET 已包含审计字段时覆盖该位置的值，保留其它业务字段和值的对应关系。
                        ((ExpressionList) set.getValues()).set(i, parameter(field));
                        found = true;
                    }
                }
            }
            // 未出现的审计字段作为新的 SET 项加入，值始终使用绑定参数。
            if (!found) update.getUpdateSets().add(new UpdateSet(new Column(name), parameter(field)));
        }
    }

    /**
     * 为单表物理 DELETE 添加 queryFields 行过滤条件，不填充审计字段。
     * 逻辑删除若由 MyBatis-Plus 转换成 UPDATE，则按 UPDATE 策略处理。
     *
     * @param delete 待原地改写的 DELETE AST
     * @throws IllegalStateException 目标表不符，或使用需要专门策略的多表删除
     */
    private void delete(Delete delete) {
        // 不自动支持 DELETE 多目标表、USING 或 JOIN，防止只约束部分删除目标。
        if (!matches(delete.getTable()))
            throw new IllegalStateException("Audited DELETE table mismatch: " + mappedStatement.getId());
        if (nonEmpty(delete.getTables()) || nonEmpty(delete.getUsingList()) || nonEmpty(delete.getJoins())) {
            throw new IllegalStateException("Audited multi-table DELETE requires an explicit policy");
        }
        // 删除选取范围中的 CTE 和子查询也要处理，不能只给最外层加条件。
        processWithItems(delete.getWithItemsList());
        processWhereSubSelect(delete.getWhere(), "");
        // DELETE 没有 SET 填充阶段；复用与读取／更新一致的 queryFields 作为行过滤条件。
        delete.setWhere(andExpression(delete.getTable(), delete.getWhere(), ""));
    }
}
