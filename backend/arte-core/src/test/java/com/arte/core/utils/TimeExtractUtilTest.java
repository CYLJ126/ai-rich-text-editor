package com.arte.core.utils;

import cn.hutool.core.lang.Pair;
import cn.hutool.core.util.StrUtil;
import com.arte.core.enums.MyTimeUnit;
import org.junit.Test;

public class TimeExtractUtilTest {

    @Test
    public void extractLineTime() {
        // 取最后一个时间，返回内容为去除“-2h”后的剩余内容
        String line = " #CFCA 签署相关3h需求、接口问题讨论-2h；";
        Pair<String, Double> pair = TimeExtractUtil.extractLineTime(line, MyTimeUnit.MINUTE);
        assert StrUtil.equals(pair.getKey(), " #CFCA 签署相关3h需求、接口问题讨论；");
        assert pair.getValue() == 120.0;

        // 时间在中间，返回内容为去除“-30min”后的剩余内容
        line = "#海港人寿、华泰财联调支持-30min；——已完成";
        pair = TimeExtractUtil.extractLineTime(line, MyTimeUnit.MINUTE);
        assert StrUtil.equals(pair.getKey(), "#海港人寿、华泰财联调支持；——已完成");
        assert pair.getValue() == 30.0;

        // 不带-的时间
        line = "安心账户接口联调1h";
        pair = TimeExtractUtil.extractLineTime(line, MyTimeUnit.MINUTE);
        assert StrUtil.equals(pair.getKey(), "安心账户接口联调");
        assert pair.getValue() == 60.0;

        // 组合时间，有h也有min
        line = "泰康进度确认、养老需求沟通-4h30min";
        pair = TimeExtractUtil.extractLineTime(line, MyTimeUnit.HOUR);
        assert StrUtil.equals(pair.getKey(), "泰康进度确认、养老需求沟通");
        assert pair.getValue() == 4.5;

        line = "项目开发-1q3m";
        pair = TimeExtractUtil.extractLineTime(line, MyTimeUnit.QUARTER);
        assert StrUtil.equals(pair.getKey(), "项目开发");
        assert Math.round(pair.getValue()) == 2;

        line = "项目战略1y2q3m";
        pair = TimeExtractUtil.extractLineTime(line, MyTimeUnit.MONTH);
        assert StrUtil.equals(pair.getKey(), "项目战略");
        assert Math.round(pair.getValue()) == 21;
    }

    @Test
    public void extractLinesTime() {
        String content = "退款接口整理：安盟用-0.5h；\n" +
                "#安盟接口沟通，10:00 - 11:00-0.5h；\n" +
                "技术支持-4.5h：\n" +
                " 华农测试支持；";
        double totalTime = TimeExtractUtil.extractLinesTime(content, MyTimeUnit.HOUR);
        assert totalTime == 5.5;
    }
}