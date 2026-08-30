package com.nip.app.service.base;

import com.nip.app.api.base.SummaryService;
import com.nip.app.common.enums.SummaryOperationTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;

@Slf4j
//@RunWith(SpringRunner.class)
//@SpringBootTest
public class SummaryServiceImplTest {

    private final SummaryService summaryService = new SummaryServiceImpl();

    @Test
    public void formatContent() {
        String content = """
                E户通项目负责人-38.50h
                1. 安心账户编码、联调-3.00h；
                	1. 安心账户转账超长截断还是失败-周燕；
                	2. 把联调代码迁移过来；
                	5. 先发一遍，确认环境是否可用；
                2. 安心账户需求、设计调整、评审-18.00h；
                	1. ——找Iris确认；
                3. 奥克斯物业需求了解、农行多级账户产品咨询-1.00h；
                4. 技术支持-1.00h；
                	1. 安盟联调支持-0.50h；
                	2. 国元问题、对账问题等；
                5. 农行子账户体系产品了解；——胡振-0.50h；
                                
                NobodyIsPerfect-2.50h
                1. 日课打分时自动更新周对应分数和进度-0.50h；
                3. 总结内容重新整理-2.00h；
                	2. 去除后缀时间后再合并；
                	4. 识别开发和技术支持类型，分开；
                	
                """;
        String result = summaryService.formatContent(content, SummaryOperationTypeEnum.FORMAT_SERIAL_NO);
        System.out.println(result);
    }

    @Test
    public void testFormatContent() {
        String content = """
                管理-5.50h；
                2. 保险周会；
                4. 面试；
                1. 钱群，#腾讯会议：294-833-038，16:00 - 16:30；
                	1. 翟鹏宇-面试通过，预计1-2周能到岗；
                6. 与林纯、Karl规划后续人员支持；
                	1. 13号需求评审；
                	2. 14号设计评审；
                	3. 17号正式开发；
                7. 周报、总结整理；
                8. 周会；
                9. 其他；
                                
                E户通项目负责人-26.60h
                3. 编码；
                1. 安心账户编码；
                1. 安心账户联调；
                2. EHT-3036-聚富通查询响应时，防重流水唯一键优化；
                4. E 户通1.53.3 编码；
                5. 技术支持；
                	2. 华泰财、阳光10-28没有回单问题解决；
                	5. 泰康人寿河北上线；
                6. 需求；
                	3. 安心账户需求整理、确认；
                4. 东海航运沟通；
                6. 黄河财相关沟通；——预计月中上线；
                7. 华泰财非见费业务上线E户通问题沟通；
                8. 京东安联增加总部、北分、上分机构业务问题沟通；——待银行回复如何增加子账号；
                10. 泰康协议、上线问题沟通；
                                
                融服保项目负责人-5.20h
                1. 项目跟进；
                	1. 壹账通进度和问题沟通；
                	2. 壹账通侧生产环境准备相关问题支持；
                """;
        String result = summaryService.formatContent(content, SummaryOperationTypeEnum.FORMAT_SUMMARY);
        System.out.println(result);
    }
}