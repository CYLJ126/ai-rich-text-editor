package com.nip.app.service.base;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.nip.app.api.base.TagRelationService;
import com.nip.app.mapper.base.TagRelationMapper;
import com.nip.app.pojo.base.TagRelationDto;
import com.nip.app.pojo.base.TagRelationPo;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

/**
 * <p>
 * 标签关系表 服务实现类
 * </p>
 *
 * @author zhangsc
 * @since 2025-12-20
 */
@Service
public class TagRelationServiceImpl extends ServiceImpl<TagRelationMapper, TagRelationDto> implements TagRelationService {

    @Override
    public List<TagRelationDto> listTagIds(List<Integer> sourceId) {
        QueryWrapper<TagRelationDto> tagIdsQueryWrapper = new QueryWrapper<>();
        tagIdsQueryWrapper.in(TagRelationPo.COL_SOURCE_ID, sourceId);
        return list(tagIdsQueryWrapper);
    }

    @Override
    public List<TagRelationDto> listSourceIds(Collection<Integer> tags) {
        QueryWrapper<TagRelationDto> sourceIdsQueryWrapper = new QueryWrapper<>();
        sourceIdsQueryWrapper.in(TagRelationPo.COL_TAG_ID, tags);
        return list(sourceIdsQueryWrapper);
    }

    @Override
    public List<TagRelationDto> listTags(TagRelationDto param) {
        QueryWrapper<TagRelationDto> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(ObjectUtil.isNotNull(param.getSourceId()), TagRelationPo.COL_SOURCE_ID, param.getSourceId());
        queryWrapper.eq(ObjectUtil.isNotNull(param.getTagId()), TagRelationPo.COL_TAG_ID, param.getTagId());
        queryWrapper.eq(ObjectUtil.isNotNull(param.getTagType()), TagRelationPo.COL_TAG_TYPE, param.getTagType());
        return list(queryWrapper);
    }
}
