package com.arte.core.interceptor;

import com.arte.core.annotations.MybatisParams;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.lang.reflect.Method;
import java.util.*;

/**
 * 旧业务审计／创建人条件插件。方法注解 > Mapper 注解 > 实体注解。
 * 查询在生成缓存键前改写；写操作包装 SqlSource，使 SIMPLE/REUSE/BATCH 每次执行都绑定当前审计值。
 * 不保存 SQL 阶段或审计值的 ThreadLocal。此插件不替代业务授权，也不负责租户隔离。
 *
 * @author zhangsc
 * @since 2025/7/21 20:11
 */
public abstract class MybatisInterceptor implements InnerInterceptor {
    private static final ThreadLocal<IgnoreScope> IGNORE_SCOPE = new ThreadLocal<>();
    private final SqlCommandType commandType;

    protected MybatisInterceptor(SqlCommandType commandType) {
        this.commandType = commandType;
    }

    /**
     * 仅用于可信同步代码，必须 try-with-resources；不会传播到 Reactor 或异步任务。
     */
    public static IgnoreScope ignoreScope() {
        return new IgnoreScope();
    }

    /**
     * 忽略拦截器作用域。
     * 生命周期：通过实现 AutoCloseable 接口，在 try-with-resources 语句中使用，确保拦截器作用域在当前线程内按顺序关闭。
     */
    public static final class IgnoreScope implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private final IgnoreScope previous = IGNORE_SCOPE.get();
        private boolean closed;

        private IgnoreScope() {
            IGNORE_SCOPE.set(this);
        }

        @Override
        public void close() {
            if (closed) return;
            if (Thread.currentThread() != owner || IGNORE_SCOPE.get() != this) {
                throw new IllegalStateException("Interceptor scopes must close in nesting order on their opening thread");
            }
            if (previous == null) IGNORE_SCOPE.remove();
            else IGNORE_SCOPE.set(previous);
            closed = true;
        }
    }

    @Override
    public void beforeQuery(Executor executor, MappedStatement ms, Object parameter, RowBounds rowBounds,
                            ResultHandler resultHandler, BoundSql boundSql) {
        if (commandType != SqlCommandType.SELECT) return;
        MybatisParams annotation = annotation(ms, parameter);
        if (annotation != null && annotation.queryFields().length != 0) {
            AuditSqlRewriter.rewrite(ms, boundSql, annotation);
        }
    }

    @Override
    public void beforeUpdate(Executor executor, MappedStatement ms, Object parameter) {
        if (commandType == SqlCommandType.SELECT || ms.getSqlCommandType() != commandType) return;
        // 元数据只安装一次；包装器自身无每次执行的可变状态。
        synchronized (ms) {
            if (!(ms.getSqlSource() instanceof AuditedSqlSource)) {
                SystemMetaObject.forObject(ms).setValue("sqlSource", new AuditedSqlSource(ms, ms.getSqlSource()));
            }
        }
    }

    private record AuditedSqlSource(MappedStatement statement, SqlSource delegate) implements SqlSource {
        @Override
        public BoundSql getBoundSql(Object parameter) {
            BoundSql original = delegate.getBoundSql(parameter);
            MybatisParams annotation = annotation(statement, parameter);
            if (annotation == null) return original;
            // 不修改原 SqlSource 返回的对象，保留 foreach/bind 的额外参数。
            BoundSql bound = new BoundSql(statement.getConfiguration(), original.getSql(), original.getParameterMappings(), parameter);
            original.getAdditionalParameters().forEach(bound::setAdditionalParameter);
            AuditSqlRewriter.rewrite(statement, bound, annotation);
            return bound;
        }
    }

    /**
     * 获取会影响当前 SQL 执行的注解，方法注解 > Mapper 注解 > 实体注解
     *
     * @param ms        映射语句
     * @param parameter 参数
     * @return 注解，或 null
     */
    private static MybatisParams annotation(MappedStatement ms, Object parameter) {
        if (IGNORE_SCOPE.get() != null) return null;
        int split = ms.getId().lastIndexOf('.');
        MybatisParams annotation = null;
        if (split > 0) {
            try {
                Class<?> mapper = Class.forName(ms.getId().substring(0, split));
                String methodName = ms.getId().substring(split + 1);
                for (Method method : mapper.getMethods()) {
                    if (!method.getName().equals(methodName)) continue;
                    // 获取 mapper 方法注解
                    MybatisParams candidate = method.getAnnotation(MybatisParams.class);
                    if (candidate != null) {
                        if (annotation != null && !annotation.equals(candidate)) {
                            throw new IllegalStateException("Conflicting overloaded mapper annotations: " + ms.getId());
                        }
                        annotation = candidate;
                    }
                }
                // 获取 mapper 注解
                if (annotation == null) annotation = mapper.getAnnotation(MybatisParams.class);
            } catch (ClassNotFoundException e) {
                // XML namespace 可以不是接口；继续检查实体注解。
            }
        }
        if (annotation == null)
            // 获取实体注解
            annotation = entityAnnotation(parameter, Collections.newSetFromMap(new IdentityHashMap<>()));
        return annotation == null || annotation.ignore() ? null : annotation;
    }

    /**
     * 获取实体类上的注解
     * 若遇 Map/Collection 则递归检查其元素
     *
     * @param parameter 实体类
     * @param seen      避环集合
     * @return 实体类上的注解，或 null
     */
    private static MybatisParams entityAnnotation(Object parameter, Set<Object> seen) {
        if (parameter == null || !seen.add(parameter)) return null;
        if (parameter instanceof Map<?, ?> map) {
            MybatisParams found = null;
            for (Object value : map.values()) {
                MybatisParams candidate = entityAnnotation(value, seen);
                if (candidate != null) {
                    if (found != null && !found.equals(candidate))
                        throw new IllegalStateException("Conflicting entity audit annotations");
                    found = candidate;
                }
            }
            return found;
        }
        if (parameter instanceof Iterable<?> items) {
            MybatisParams found = null;
            for (Object value : items) {
                MybatisParams candidate = entityAnnotation(value, seen);
                if (candidate != null) {
                    if (found != null && !found.equals(candidate))
                        throw new IllegalStateException("Conflicting batch audit annotations");
                    found = candidate;
                }
            }
            return found;
        }
        if (!parameter.getClass().getPackageName().startsWith("com.arte")) return null;
        for (Class<?> type = parameter.getClass(); type != null; type = type.getSuperclass()) {
            MybatisParams annotation = type.getAnnotation(MybatisParams.class);
            if (annotation != null) return annotation;
        }
        return null;
    }

    /**
     * 驼峰转下划线
     *
     * @param name 驼峰命名字符串
     * @return 下划线命名字符串
     */
    public static String camelToSnake(String name) {
        return name.replaceAll("([A-Z])", "_$1").replaceFirst("^_", "").toLowerCase(Locale.ROOT);
    }
}
