package com.arte.app.web.controller.base;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.text.CharSequenceUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.arte.app.api.base.TagService;
import com.arte.app.pojo.base.TagDto;
import com.arte.app.pojo.base.TagPo;
import com.arte.app.pojo.base.param.TagParam;
import com.arte.core.enums.StatusEnum;
import com.arte.core.pojo.ResultContext;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * <p>
 * 标签表 前端控制器
 * </p>
 *
 * @author zhangsc
 * @since 2025-02-08
 */
@RestController
@RequestMapping("/base/tag")
public class TagController {

    @Resource
    private TagService tagService;

    @PostMapping("/listTags")
    @PreAuthorize("@pcs.check('tag:list')")
    public ResultContext<List<TagDto>> listTags(@RequestBody TagParam param) {
        QueryWrapper<TagDto> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(Objects.nonNull(param.getId()), TagPo.COL_ID, param.getId());
        queryWrapper.in(CollUtil.isNotEmpty(param.getFatherIds()), TagPo.COL_FATHER_ID, param.getFatherIds());
        queryWrapper.eq(CharSequenceUtil.isNotBlank(param.getName()), TagPo.COL_NAME, param.getName());
        queryWrapper.eq(Objects.nonNull(param.getStatus()), TagPo.COL_STATUS, param.getStatus());
        queryWrapper.orderByAsc(TagPo.COL_ORDER_ID);
        return ResultContext.success(tagService.list(queryWrapper));
    }

    @PostMapping("/listRecursive")
    @PreAuthorize("@pcs.check('tag:list')")
    public ResultContext<List<TagDto>> listRecursive(@RequestBody TagParam param) {
        param.setStatus(StatusEnum.DOING);
        return ResultContext.wrap(param, tagService::listRecursive);
    }

    @PostMapping("/addTag")
    @PreAuthorize("@pcs.check('tag:add')")
    public ResultContext<Boolean> addTag(@RequestBody TagDto param) {
        param.setId(null);
        if (Objects.isNull(param.getOrderId())) {
            param.setOrderId(tagService.findMaxOrder(param.getFatherId()) + 1);
        }
        boolean save = tagService.save(param);
        if (!save) {
            return ResultContext.fail();
        }
        tagService.refresh();
        return ResultContext.success(Boolean.TRUE);
    }

    @PostMapping("/updateTag")
    @PreAuthorize("@pcs.check('tag:update')")
    public ResultContext<Boolean> updateTag(@RequestBody TagDto param) {
        boolean update = tagService.updateById(param);
        if (!update) {
            return ResultContext.fail();
        }
        tagService.refresh();
        return ResultContext.success(Boolean.TRUE);
    }

    @PostMapping("/deleteTag")
    @PreAuthorize("@pcs.check('tag:delete')")
    public ResultContext<Boolean> deleteTag(@RequestBody TagParam param) {
        Boolean removed = tagService.removeRecursive(param.getId());
        if (!removed) {
            return ResultContext.fail();
        }
        tagService.refresh();
        return ResultContext.success(Boolean.TRUE);
    }

    @PostMapping("/reorderTags")
    @PreAuthorize("@pcs.check('tag:update')")
    public ResultContext<Boolean> reorderTags(@RequestBody List<TagDto> list) {
        return ResultContext.wrap(() -> tagService.reorderTags(list, 1, true));
    }

}
