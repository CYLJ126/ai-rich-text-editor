package com.nip.app.api.base;

import com.baomidou.mybatisplus.extension.service.IService;
import com.nip.app.pojo.base.TagRelationDto;

import java.util.Collection;
import java.util.List;

/**
 * <p>
 * 标签关系表 服务类
 * </p>
 *
 * @author zhangsc
 * @since 2025-12-20
 */
public interface TagRelationService extends IService<TagRelationDto> {

    List<TagRelationDto> listTagIds(List<Integer> sourceId);

    List<TagRelationDto> listSourceIds(Collection<Integer> tagId);

    List<TagRelationDto> listTags(TagRelationDto param);
}
