package com.arte.app.pojo.home.website;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.arte.app.common.enums.WebsiteResolveTypeEnum;
import com.arte.core.enums.HttpRequestTypeEnum;
import com.arte.core.enums.StatusEnum;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * <p>
 * 资讯网站信息
 * </p>
 *
 * @author zhangsc
 * @since 2025-04-12
 */
@Getter
@Setter
@ToString
@Accessors(chain = true)
@TableName("arte_home_website_info")
public class WebsiteInfoPo implements Serializable {

    @Serial
    private static final long serialVersionUID = 478243791725624766L;
    /**
     * ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * 网站名
     */
    private String name;

    /**
     * 模块名，如推荐、博客、热门
     */
    private String module;

    /**
     * 类型，对应一级标签“新闻资讯”下的列表
     */
    private String type;

    /**
     * 顺序
     */
    private Integer websiteOrder;

    /**
     * 状态
     */
    private StatusEnum websiteStatus;

    /**
     * 图标地址
     */
    private String logoUrl;

    /**
     * 请求类型：如 POST、GET等
     */
    private HttpRequestTypeEnum requestType;

    /**
     * 解析类型
     */
    private WebsiteResolveTypeEnum resolveType;

    /**
     * 资讯列表地址
     */
    private String informationUrl;

    /**
     * 模块地址
     */
    private String moduleUrl;

    /**
     * 格式化模板，用于替换生成最终的新闻访问链接
     */
    private String formatter;

    /**
     * 映射字段
     */
    private String fieldMapping;

    /**
     * 新闻列表 json 路径
     */
    private String dataPath;

    /**
     * 条件值路径
     */
    private String conditionPath;

    /**
     * 条件路径下的值等于此值时才认为请求成功
     */
    private String conditionValue;

    /**
     * 请求头
     * ## 分隔多个请求头，|| 连接请求头的名称和值
     */
    private String requestHeaders;

    /**
     * Logo 获取时要加的请求头
     */
    private String logoRequestHeaders;

    /**
     * 请求体
     */
    private String requestBody;

    /**
     * 是否需要代理
     */
    private Boolean proxy;

    public static final String COL_ID = "id";

    public static final String COL_NAME = "name";

    public static final String COL_ORDER = "website_order";

    public static final String COL_STATUS = "website_status";

    public static final String COL_MODULE = "module";

    public static final String COL_TYPE = "type";

    public static final String COL_LOGO_URL = "logo_url";

    public static final String COL_MODULE_URL = "module_url";

    public static final String COL_REQUEST_TYPE = "request_type";

    public static final String COL_INFORMATION_URL = "information_url";

    public static final String COL_FORMATTER = "formatter";

    public static final String COL_FIELD_MAPPING = "field_mapping";

    public static final String COL_DATA_PATH = "data_path";

    public static final String COL_CONDITION_PATH = "condition_path";

    public static final String COL_CONDITION_VALUE = "condition_value";

    public static final String COL_REQUEST_HEADERS = "request_headers";

    public static final String COL_REQUEST_BODY = "request_body";
}
