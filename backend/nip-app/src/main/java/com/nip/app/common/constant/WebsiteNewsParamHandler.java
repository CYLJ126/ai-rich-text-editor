package com.nip.app.common.constant;


import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.text.CharSequenceUtil;

/**
 * 新闻链接参数转换类型
 *
 * @author zhangsc
 * @since 2025/4/14 9:38
 */
public class WebsiteNewsParamHandler {

    private WebsiteNewsParamHandler() {
    }

    public static final String WEBSITE_TYPE_PREFIX = "websiteType:";

    public static final String WEBSITE_NEWS_PREFIX = "websiteNews:";

    public static final String WEBSITE_LOGO_PREFIX = "websiteLogo:";

    public static String transfer(String type) {
        if (CharSequenceUtil.equals("unix-milli", type)) {
            /*
             * 遇此占位符，转成 unix 毫秒级时间戳，13位长度
             * 数据库中 nip_home_website_info 表的information_url字段占位符要与此一致
             */
            return String.valueOf(System.currentTimeMillis());
        }
        return CharSequenceUtil.EMPTY;
    }


    public static String transfer(String type, String value) {
        if (CharSequenceUtil.equals("yyyyMMdd", type)) {
            // 日期解析
            DateTime time = DateUtil.parse(value);
            return DateUtil.format(time, DatePattern.PURE_DATE_PATTERN);
        } else if (CharSequenceUtil.equals("cebnetJsonTransfer", type)) {
            // 中国电子银行网的奇葩逻辑，"articleLink":"https://www.cebnet.com.cn/json/20250421/102986477.json" 要转为 https://www.cebnet.com.cn/20250421/102986477.html
            value = CharSequenceUtil.replace(value, "/json", "");
            value = CharSequenceUtil.replace(value, "json", "html");
        } else if (CharSequenceUtil.startWith(type, "subBefore")) {
            // 配置时，第一位从 0 开始，如 ../yaowen/liebiao/202504/content_7020724.htm，则配置 subBefore2，转换为 /yaowen/liebiao/202504/content_7020724.htm
            int startIndex = Integer.parseInt(CharSequenceUtil.subAfter(type, "subBefore", false));
            return CharSequenceUtil.sub(value, startIndex, value.length());
        }
        return value;
    }
}
