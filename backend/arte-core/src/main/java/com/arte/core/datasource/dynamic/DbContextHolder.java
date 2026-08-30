package com.arte.core.datasource.dynamic;


/**
 * 设置、获取数据源
 *
 * @author zhangsc ≧◔◡◔≦
 * @version 1.0.0 ✵
 * @since 2021/2/6 16:09 ✾
 **/
public class DbContextHolder {

    private static final ThreadLocal<String> CONTEXT_HOLDER = new ThreadLocal<>();

    /**
     * 设置数据源
     *
     * @param whichDataSource 数据源对应的键名
     */
    public static void setDataSourceKey(String whichDataSource) {
        CONTEXT_HOLDER.set(whichDataSource);
    }

    /**
     * 取得当前数据源
     *
     * @return 当前数据源键名
     */
    public static String getDataSourceKey() {
        return CONTEXT_HOLDER.get();
    }

    /**
     * 清除上下文数据
     */
    public static void clearDataSourceKey() {
        CONTEXT_HOLDER.remove();
    }
}
