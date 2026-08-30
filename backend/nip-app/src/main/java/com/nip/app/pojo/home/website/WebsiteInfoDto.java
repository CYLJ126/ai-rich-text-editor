package com.nip.app.pojo.home.website;

import cn.hutool.core.text.CharSequenceUtil;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
@ToString(callSuper = true)
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
public class WebsiteInfoDto extends WebsiteInfoPo implements Serializable {

    @Serial
    private static final long serialVersionUID = 4384598735612697329L;

    private transient List<FieldMapping> fieldMappings;

    /**
     * 新闻标签名
     */
    @TableField(exist = false)
    private String tagName;

    /**
     * <标题，链接>
     */
    @TableField(exist = false)
    private List<NewsVo> newsList = new ArrayList<>();

    public void mapFields() {
        if (CharSequenceUtil.isBlank(this.getFieldMapping())) {
            this.setFieldMappings(Collections.emptyList());
            return;
        }
        String[] fields = this.getFieldMapping().trim().split(",");
        List<FieldMapping> fieldMappingsTemp = new ArrayList<>();
        for (String str : fields) {
            String[] split = str.split("\\|\\|", 2);
            FieldMapping mapping = new FieldMapping((CharSequenceUtil.startWith(split[0], "/") ? "" : "/") + split[0], split[1]);
            fieldMappingsTemp.add(mapping);
        }
        this.setFieldMappings(fieldMappingsTemp);
    }
}
