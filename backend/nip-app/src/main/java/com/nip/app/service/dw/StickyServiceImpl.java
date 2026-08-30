package com.nip.app.service.dw;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.nip.app.api.base.TagRelationService;
import com.nip.app.api.dw.StickyService;
import com.nip.app.common.enums.DwStickyShowTypeEnum;
import com.nip.app.mapper.dw.StickyMapper;
import com.nip.app.pojo.base.TagRelationDto;
import com.nip.app.pojo.dw.StickyDto;
import com.nip.app.pojo.dw.StickyPo;
import com.nip.app.pojo.dw.param.StickyParam;
import com.nip.core.enums.StatusEnum;
import com.nip.core.enums.YesOrNoEnum;
import com.nip.core.pojo.PageView;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * <p>
 * 便笺表 服务实现类
 * </p>
 *
 * @author zhangsc
 * @since 2025-06-20
 */
@Service
public class StickyServiceImpl extends ServiceImpl<StickyMapper, StickyDto> implements StickyService {

    @Resource
    private TagRelationService tagrelationService;

    @Override
    public PageView<StickyDto> listStickies(StickyParam param) {
        QueryWrapper<StickyDto> queryWrapper = new QueryWrapper<>();
        // 如果 tags 不为空，先以 tags 过滤出要查询的便笺
        if (CollUtil.isNotEmpty(param.getTags())) {
            // 级联查出所有下级标签 TODO
            // 查出所有便笺 ID
            List<TagRelationDto> tagRelations = tagrelationService.listSourceIds(param.getTags());
            if (CollUtil.isNotEmpty(tagRelations)) {
                queryWrapper.in(StickyPo.COL_ID, tagRelations.stream().map(TagRelationDto::getSourceId).toList());
            }
        }
        queryWrapper.eq(Objects.nonNull(param.getId()), StickyPo.COL_ID, param.getId());
        queryWrapper.ne(StickyPo.COL_STATUS, StatusEnum.CLOSED);
        queryWrapper.like(Objects.nonNull(param.getTitle()), StickyPo.COL_TITLE, param.getTitle());
        queryWrapper.like(Objects.nonNull(param.getContent()), StickyPo.COL_CONTENT, param.getContent());
        // 页面只显示截止日期，只要截止日期比指定日期晚，就显示
        queryWrapper.ge(Objects.nonNull(param.getEndDate()), StickyPo.COL_END_DATE, param.getEndDate());
        return PageView.success(page(param, queryWrapper));
    }

    @Override
    public StickyDto getStickyById(StickyDto param) {
        Assert.notNull(param.getId(), "便笺查询 ID 不能为空");
        StickyDto sticky = getById(param.getId());
        List<TagRelationDto> tagRelations = tagrelationService.listTagIds(Collections.singletonList(param.getId()));
        if (CollUtil.isNotEmpty(tagRelations)) {
            sticky.setTags(tagRelations.stream().map(TagRelationDto::getTagId).toList());
        }
        return sticky;
    }

    @Override
    public StickyDto addSticky(StickyDto param) {
        param.setStartDate(LocalDate.now());
        param.setEndDate(LocalDate.now());
        param.setStatus(StatusEnum.DOING);
        param.setFoldFlag(YesOrNoEnum.YES);
        param.setShowType(DwStickyShowTypeEnum.TEXT);
        return save(param) ? param : null;
    }

    @Override
    public boolean updateSticky(StickyDto param) {
        UpdateWrapper<StickyDto> wrapper = new UpdateWrapper<>();
        wrapper.eq(StickyPo.COL_ID, param.getId())
                .set(StickyPo.COL_TITLE, param.getTitle())
                .set(StickyPo.COL_CONTENT, param.getContent())
                .set(StickyPo.COL_END_DATE, param.getEndDate());
        return update(wrapper);
    }

    @Override
    public boolean resizeSticky(StickyDto param) {
        UpdateWrapper<StickyDto> updateWrapper = new UpdateWrapper<>();
        updateWrapper.set(StickyPo.COL_WIDTH, param.getWidth())
                .set(StickyPo.COL_HEIGHT, param.getHeight())
                .eq(StickyPo.COL_ID, param.getId());
        return update(updateWrapper);
    }

    @Override
    public boolean orderSticky(StickyDto param) {
        UpdateWrapper<StickyDto> updateWrapper = new UpdateWrapper<>();
        updateWrapper.set(StickyPo.COL_ORDER_ID, param.getOrderId())
                .eq(StickyPo.COL_ID, param.getId());
        return update(updateWrapper);
    }

    @Override
    public boolean foldSticky(StickyDto param) {
        UpdateWrapper<StickyDto> updateWrapper = new UpdateWrapper<>();
        updateWrapper.set(StickyPo.COL_FOLD_FLAG, param.getFoldFlag())
                .eq(StickyPo.COL_ID, param.getId());
        return update(updateWrapper);
    }

    @Override
    public boolean switchThemeColor(StickyDto param) {
        UpdateWrapper<StickyDto> updateWrapper = new UpdateWrapper<>();
        updateWrapper.set(StickyPo.COL_THEME_COLOR, StrUtil.removePrefix(param.getThemeColor(), "#"))
                .eq(StickyPo.COL_ID, param.getId());
        return update(updateWrapper);
    }

    @Override
    public boolean deleteSticky(StickyDto param) {
        UpdateWrapper<StickyDto> updateWrapper = new UpdateWrapper<>();
        updateWrapper.set(StickyPo.COL_STATUS, StatusEnum.CLOSED)
                .eq(StickyPo.COL_ID, param.getId());
        return update(updateWrapper);
    }
}
