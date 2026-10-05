package com.arte.core.interceptor;

import com.arte.core.annotations.MybatisParams;
import com.arte.core.pojo.UserContext;
import com.arte.core.pojo.UserOnlineInfo;
import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 复现应用的插件顺序，验证 MyBatis-Plus 分页、BaseMapper 与乐观锁兼容性。 */
class MybatisPlusInterceptorCompatibilityTest {
    @TableName("demo") public static class Row {
        @TableId(type=IdType.INPUT) private Integer id;
        private Integer payload;
        @Version private Integer version;
        public Integer getId() { return id; } public void setId(Integer id) { this.id=id; }
        public Integer getPayload() { return payload; } public void setPayload(Integer payload) { this.payload=payload; }
        public Integer getVersion() { return version; } public void setVersion(Integer version) { this.version=version; }
    }
    @MybatisParams("demo") public interface Mapper extends BaseMapper<Row> { }
    private JdbcDataSource dataSource;
    private SqlSessionFactory factory;
    @BeforeEach void setup() throws Exception {
        dataSource=new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (var connection=dataSource.getConnection();var sql=connection.createStatement()) {
            sql.execute("CREATE TABLE demo(id INT PRIMARY KEY,payload INT,version INT,create_by VARCHAR(128),create_time TIMESTAMP,update_by VARCHAR(128),update_time TIMESTAMP)");
            sql.execute("INSERT INTO demo(id,payload,version,create_by) VALUES(1,0,0,'alice'),(2,0,0,'bob'),(3,0,0,'alice')");
        }
        var config=new MybatisConfiguration();
        config.setEnvironment(new Environment("plus",new JdbcTransactionFactory(),dataSource));
        var plugin=new MybatisPlusInterceptor();
        plugin.addInnerInterceptor(new MybatisQueryInterceptor());
        plugin.addInnerInterceptor(new MybatisInsertInterceptor());
        plugin.addInnerInterceptor(new MybatisUpdateInterceptor());
        plugin.addInnerInterceptor(new MybatisDeleteInterceptor());
        plugin.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        plugin.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        config.addInterceptor(plugin);config.addMapper(Mapper.class);
        factory=new MybatisSqlSessionFactoryBuilder().build(config);
        UserContext.setUserOnlineInfo(new UserOnlineInfo().setUserName("alice"));
    }
    @AfterEach void cleanup() throws Exception {
        UserContext.clear();
        try (var connection=dataSource.getConnection();var sql=connection.createStatement()) { sql.execute("DROP ALL OBJECTS"); }
    }
    @Test void paginationCountAndRecordsApplyTheSameOwnerCondition() {
        try (var session=factory.openSession()) {
            var page=session.getMapper(Mapper.class).selectPage(new Page<Row>(2,1),Wrappers.<Row>query().orderByAsc("id"));
            assertEquals(2,page.getTotal());
            assertEquals(List.of(3),page.getRecords().stream().map(Row::getId).toList());
        }
    }
    @Test void optimisticVersionAndOwnerGuardBothApplyToBaseMapperUpdates() {
        try (var session=factory.openSession()) {
            var mapper=session.getMapper(Mapper.class);
            var own=mapper.selectById(1);own.setPayload(7);
            assertEquals(1,mapper.updateById(own));
            assertEquals(1,own.getVersion());
            var stale=new Row();stale.setId(1);stale.setPayload(99);stale.setVersion(0);
            assertEquals(0,mapper.updateById(stale));
            var other=new Row();other.setId(2);other.setPayload(99);other.setVersion(0);
            assertEquals(0,mapper.updateById(other));
            assertEquals(0,mapper.deleteById(2));
            session.commit();
        }
        try (var connection=dataSource.getConnection();var sql=connection.createStatement();var rows=sql.executeQuery("SELECT payload,version FROM demo WHERE id=1")) {
            assertTrue(rows.next());assertEquals(7,rows.getInt(1));assertEquals(1,rows.getInt(2));
        } catch (java.sql.SQLException error) { fail(error); }
    }
}
