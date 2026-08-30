package com.nip.app.web.controller.base;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.nip.app.api.base.TagRelationService;
import com.nip.app.pojo.base.TagDto;
import com.nip.app.pojo.base.TagPo;
import com.nip.app.pojo.base.TagRelationDto;
import com.nip.app.pojo.base.TagRelationPo;
import com.nip.app.service.base.TagServiceImpl;
import com.nip.core.pojo.ResultContext;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * <p>
 * 标签关系表 前端控制器
 * </p>
 *
 * @author zhangsc
 * @since 2025-12-20
 */
@RestController
@RequestMapping("/tag-relation")
public class TagRelationController {

    @Resource
    private TagRelationService tagrelationService;

    @PostMapping("/addTagRelation")
    @PreAuthorize("@pcs.check('tagRelation:add')")
    public ResultContext<Boolean> addTagRelation(@RequestBody TagRelationDto param) {
        Assert.notNull(param.getTagType(), "标签类型不能为空");
        Assert.notNull(param.getTagId(), "标签 ID 不能为空");
        Assert.notNull(param.getSourceId(), "源 ID 不能为空");
        List<TagRelationDto> exists = tagrelationService.listTags(param);
        if (CollUtil.isNotEmpty(exists)) {
            // 标签关系存在时，再添加同样关系则直接返回成功
            return ResultContext.success();
        }
        return ResultContext.wrap(param, tagrelationService::save);
    }

    @PostMapping("/deleteTagRelation")
    @PreAuthorize("@pcs.check('tagRelation:delete')")
    public ResultContext<Boolean> deleteTagRelationById(@RequestBody TagRelationDto param) {
        QueryWrapper<TagRelationDto> queryWrapper = getQueryWrapper(param);
        return ResultContext.wrap(() -> tagrelationService.remove(queryWrapper));
    }

    @PostMapping("/listTagRelations")
    @PreAuthorize("@pcs.check('tagRelation:list')")
    public ResultContext<List<TagDto>> listTagRelations(@RequestBody TagRelationDto param) {
        QueryWrapper<TagRelationDto> queryWrapper = getQueryWrapper(param);
        List<TagRelationDto> relations = tagrelationService.list(queryWrapper);
        List<TagDto> tags = relations.stream().map(relation -> TagServiceImpl.getTagById(relation.getTagId()))
                .sorted(Comparator.comparing(TagPo::getId)).toList();
        return ResultContext.success(tags);
    }

    private static QueryWrapper<TagRelationDto> getQueryWrapper(TagRelationDto param) {
        Assert.notNull(param.getTagType(), "标签类型不能为空");
        Assert.notNull(param.getSourceId(), "源对象 ID 不能为空");
        QueryWrapper<TagRelationDto> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(TagRelationPo.COL_TAG_TYPE, param.getTagType());
        queryWrapper.eq(Objects.nonNull(param.getTagId()), TagRelationPo.COL_TAG_ID, param.getTagId());
        queryWrapper.eq(Objects.nonNull(param.getSourceId()), TagRelationPo.COL_SOURCE_ID, param.getSourceId());
        return queryWrapper;
    }

}
