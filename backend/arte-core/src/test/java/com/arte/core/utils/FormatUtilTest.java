package com.arte.core.utils;

import cn.hutool.core.util.StrUtil;
import org.junit.Test;

public class FormatUtilTest {

    @Test
    public void formatSerialNo() {
        String msg = """
                项目跟进、问题解决-5h；
                 #签署相关需求、接口问题讨论；
                 跟进壹账通侧本地部署；
                  确认SAAS生产部署资源是否准备好；
                   融服保打包依赖问题解决；
                  跟进壹账通侧本地部署；
                 宁波银行需求沟通时间确认；
                其他内容：
                 暂无；""";
        String formatted = FormatUtil.formatSerialNo(msg);
        System.out.println(formatted);
        System.out.println();
        String raw = FormatUtil.removeSerialNo(formatted);
        System.out.println(raw);
        // 前导空格会被转换为 \t
        assert msg.equals(raw.replace("\t", " "));
    }

    @Test
    public void removeSerialNo() {
        String msg = "1. 保险周会-1.50h；\n" +
                "\t1. E户通目标梳理、对接中客户情况；\n" +
                "2. E户通申请开发资源沟通-Mike-0.50h；\n" +
                "\t1. 需要一个开发支援；\n" +
                "3. 面试-3.70h；\n" +
                "\t面试反馈-拒绝；\n" +
                "\t面试情况沟通；\n" +
                "\t腾讯会议：853-468-861，14:10 - 15:00；\n" +
                "\t王庚辰，14:00-14:30，#腾讯会议：938-337-433；\n" +
                "\t王乐，18:30 ~ 19:00，#腾讯会议：793-921-034；\n" +
                "4. 面试相关沟通、面试问题整理-2.50h；";
        String raw = FormatUtil.removeSerialNo(msg);
        System.out.println(raw);
        assert raw.equals("保险周会-1.50h；\n" +
                "\tE户通目标梳理、对接中客户情况；\n" +
                "E户通申请开发资源沟通-Mike-0.50h；\n" +
                "\t需要一个开发支援；\n" +
                "面试-3.70h；\n" +
                "\t面试反馈-拒绝；\n" +
                "\t面试情况沟通；\n" +
                "\t腾讯会议：853-468-861，14:10 - 15:00；\n" +
                "\t王庚辰，14:00-14:30，#腾讯会议：938-337-433；\n" +
                "\t王乐，18:30 ~ 19:00，#腾讯会议：793-921-034；\n" +
                "面试相关沟通、面试问题整理-2.50h；");
    }

    @Test
    public void formatSummary() {
        String raw = """
                安心账户原型、需求完善-5.5h：
                 先发一遍，确认环境是否可用；
                安心账户原型、需求完善-0.5h：
                #国任上周四投产版本后订单大量关闭问题确认-1h；——系S3开放下单关单接口给前端
                技术支持；
                 #海港人寿、华泰财联调支持-30min；
                泰康进度确认、养老需求沟通-4h30min；
                泰康-上海工行协议推进0.5h；
                技术支持：
                 #华泰财联调支持-30min；
                               
                凯云物业协议沟通-1h；
                """;
        String formatted = FormatUtil.formatSummary(raw);
        System.out.println(formatted);
        assert StrUtil.startWith(formatted, "-14.00h");
    }

    @Test
    public void removeSub() {
        String msg = """
                E户通项目负责人-38.50h
                1. 安心账户编码、联调-3.00h；
                	1. 安心账户转账超长截断还是失败-周燕；
                	    1. 中文处理；
                	    2. 英文和数字处理；
                	2. 把联调代码迁移过来；
                	3. 先发一遍，确认环境是否可用；
                2. 安心账户需求、设计调整、评审-18.00h；
                	1. ——找Iris确认；
                3. 奥克斯物业需求了解、农行多级账户产品咨询-1.00h；
                4. 技术支持-1.00h；
                	1. 安盟联调支持-0.50h；
                	2. 国元问题、对账问题等；
                5. 农行子账户体系产品了解；——胡振-0.50h；
                                
                AIRichTextEditor-2.50h
                1. 日课打分时自动更新周对应分数和进度-0.50h；
                2. 总结内容重新整理-2.00h；
                	1. 去除后缀时间后再合并；
                	2. 识别开发和技术支持类型，分开；
                	
                 """;
        String raw = FormatUtil.removeSub(msg, 3);
        System.out.println(raw);
    }

    @Test
    public void removeTime() {
        String msg = """
                E户通项目负责人-38.50h
                1. 安心账户编码、联调-3.00h；
                	1. 安心账户转账4m超长截断还是失败-周燕；
                	    1. 中文处理；
                	    2. 英文和数字处理；
                	2. 把联调代码1y2q3m迁移过来；
                	3. 先发一遍，确认环境是否可用；
                2. 安心账户需求、设计调整、评审-18.00h；
                	1. ——找Iris确认；
                3. 奥克斯物业需求了解、农行多级账户产品咨询-1.00h；
                4. 技术支持-1.00h；
                	1. 安盟联调支持-0.50h；
                	2. 国元问题、对账问题等；
                5. 农行子账户体系产品了解；——胡振-0.50h；
                 """;
        String raw = FormatUtil.removeTime(msg);
        System.out.println(raw);
    }
}