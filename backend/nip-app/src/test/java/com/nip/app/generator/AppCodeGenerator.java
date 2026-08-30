package com.nip.app.generator;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.generator.FastAutoGenerator;
import com.baomidou.mybatisplus.generator.engine.FreemarkerTemplateEngine;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 代码生成器
 * 注意，这是重写文件的模式，有时候可能在mapper.xml中增加了自己的 SQL，为避免被重写，每次用完后把表名列表删除。
 *
 * @author zhangsc
 * @since 2024/6/17 19:38
 */
@Slf4j
public class AppCodeGenerator {

    /**
     * 数据库信息
     */
    private static final String url = "jdbc:mysql://bj-cynosdbmysql-grp-dex4oraw.sql.tencentcdb.com:25501/nip?serverTimezone=Asia/Shanghai&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true";
    private static final String username = "nip_prod";
    private static final String password = "C201@0bee-08c0eb43d606";

    /**
     * 要生成代码的表名列表，不填就会生成所有的！！！！！
     */
//    private static final List<String> tables = List.of("nip_base_tag", "nip_base_summary", "nip_dw_statistics", "nip_dw_weekly_work", "nip_dw_daily_work", "nip_dw_steps", "nip_dw_weekly_days");
    private static final List<String> tables = List.of("nip_ai_assistant");

    /**
     * 指定作者
     */
    private static final String author = "zhangsc";
    /**
     * 表示项目的模块名称
     */
    private static final String module = "nip-ai";
    /**
     * 文件输出路径
     */
    private static final String outPath = System.getProperty("user.dir") + "/" + module + "/src/main/java";
    /**
     * 忽略的表名前缀
     */
    private static final String[] prefixesIgnore = new String[]{"nip_", "nip_dw_", "nip_rbac_", "nip_ai_", "nip_home_"};
    /**
     * 忽略的表名后缀
     */
    private static final String[] suffixesIgnore = new String[]{};
    /**
     * 父包的名称
     */
    private static final String parent = "com.nip.ai";
    /**
     * 模块名称
     */
    private static final String moduleName = "";
    /**
     * 指定实体包名
     */
    private static final String entity = "pojo";
    /**
     * 指定 mapper 接口包名
     */
    private static final String mapper = "mapper";
    /**
     * 指定service接口包名
     */
    private static final String service = "api";
    /**
     * service实现类包名
     */
    private static final String serviceImpl = "service";
    /**
     * 指定控制器包名
     */
    private static final String controller = "web.controller";
    /**
     * 指定xml包名
     */
    private static final String mapperXml = "mapper";

    public static void main(String[] args) {
        FastAutoGenerator.create(url, username, password)
                // 全局配置
                .globalConfig(builder -> {
                    builder.author(author)
//                            .enableSwagger() // 开启 swagger 模式
                            .outputDir(outPath)
                            .disableOpenDir(); // 生成后不打开目录
                })
                // 包配置
                .packageConfig(builder -> {
                    builder.parent(parent)
                            .moduleName(moduleName)
                            .entity(entity)
                            .mapper(mapper)
                            .service(service)
                            .serviceImpl(serviceImpl)
                            .controller(controller)
                            .xml(mapperXml);
                })
                // 策略配置
                .strategyConfig(builder -> {
                    builder.addInclude(tables)
                            .addTablePrefix(prefixesIgnore)
                            .addFieldSuffix(suffixesIgnore)
                            .entityBuilder() // 开启生成实体类
                            .enableColumnConstant() // 开启生成字段常量
                            .enableChainModel() // 开启链式模型
                            .enableLombok() // 开启 lombok 注解
                            .enableFileOverride() // 覆盖已有文件
                            .formatFileName("%sDto")
                            .mapperBuilder()// 开启生成 mapper
                            .enableFileOverride() // 覆盖已有文件
                            .superClass(BaseMapper.class)
                            .formatMapperFileName("%sMapper") // 格式化 mapper 名称
                            .formatXmlFileName("%sMapper") // 格式化 xml 名称
                            .serviceBuilder() // 开启生成 service
                            .enableFileOverride() // 覆盖已有文件
                            .formatServiceFileName("%sService") // 格式化 service 接口文件名称
                            .formatServiceImplFileName("%sServiceImpl")
                            .controllerBuilder() // 开启生成 controller
                            .enableFileOverride() // 覆盖已有文件
                            .formatFileName("%sController")
                            .enableRestStyle();
                })
                .templateEngine(new FreemarkerTemplateEngine()) // 使用Freemarker引擎模板，默认的是Velocity引擎模板
                .execute();
        log.info("生成源文件完成");
    }
}
