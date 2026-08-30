package com.nip.app.pojo.richtext.param;

import com.nip.app.common.enums.richtext.ArticleAccessLevelEnum;
import com.nip.app.common.enums.richtext.ArticleTypeEnum;
import com.nip.app.pojo.BaseParam;
import com.nip.app.pojo.richtext.ArticleDto;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.io.Serializable;
import java.util.Set;

/**
 * 文章查询参数类
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/5/5 14:51 ✾
 **/
@EqualsAndHashCode(callSuper = true)
@Data
public class ArticleParam extends BaseParam<ArticleDto> implements Serializable {

    @Serial
    private static final long serialVersionUID = -4805882907852934661L;

    /**
     * 查询关键字
     */
    private String searchBingoText;

    /**
     * 是否搜索标题
     */
    private Boolean searchTitle;

    /**
     * 作者
     */
    private Set<String> authors;

    /**
     * 文章ID 列表
     */
    private Set<Integer> articleIds;

    /**
     * 所属目录ID
     */
    private Set<Integer> catalogIds;

    /**
     * 文章字数上限
     */
    private Integer characterCountCeil;

    /**
     * 文章字数下限
     */
    private Integer characterCountFloor;

    /**
     * 访问等级
     */
    private ArticleAccessLevelEnum accessLevel;

    /**
     * 文章类型
     */
    private ArticleTypeEnum articleType;

    /**
     * 是否语义搜索
     * 如果为是，则在 KNN 搜索中不会添加关键词匹配
     * 如搜索“苏轼”，当为是时，会搜索出豪放、诗人等相关内容；为否时，只会搜出带“苏轼”的内容
     */
    private Boolean semanticSearch;

}
