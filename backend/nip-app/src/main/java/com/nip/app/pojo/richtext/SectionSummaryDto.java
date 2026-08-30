package com.nip.app.pojo.richtext;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * <p>
 * 章节摘要表
 * </p>
 *
 * @author zhangsc
 * @since 2026-06-13
 */
@Getter
@Setter
@ToString
@Accessors(chain = true)
@TableName("nip_rt_section_summary")
public class SectionSummaryDto extends SectionSummaryPo implements Serializable {

    @Serial
    private static final long serialVersionUID = -6285496479859590846L;

}
