package com.arte.app.service.base;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.arte.app.api.base.TagRelationService;
import com.arte.app.api.base.TagService;
import com.arte.app.common.constant.DwConstant;
import com.arte.app.common.enums.TagTypeEnum;
import com.arte.app.mapper.base.TagMapper;
import com.arte.app.pojo.base.TagDto;
import com.arte.app.pojo.base.TagPo;
import com.arte.app.pojo.base.TagRelationDto;
import com.arte.app.pojo.base.param.TagParam;
import com.arte.core.interceptor.MybatisInterceptor;
import com.arte.core.pojo.UserContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <p>
 * 标签表 服务实现类
 * </p>
 *
 * @author zhangsc
 * @since 2025-02-08
 */
@Service
public class TagServiceImpl extends ServiceImpl<TagMapper, TagDto> implements TagService {

    // TODO 增加缓存层，以用户第一次访问为起始，以用户为键，放到 Redis 中
    private static final Map<Integer, TagDto> TAG_MAP = new ConcurrentHashMap<>();

    @Resource
    private TagRelationService tagRelationService;

    @PostConstruct
    public void init() {
        refresh();
    }

    public static TagDto getTagById(Integer id) {
        return TAG_MAP.get(id);
    }

    public void refresh() {
        MybatisInterceptor.ignore();
        list().forEach(tag -> TAG_MAP.put(tag.getId(), tag));
    }

    @Override
    public List<TagDto> listRecursive(TagParam param) {
        param.setCurrent(1);
        // 每个人最多只能建 9999 个标签
        param.setSize(9999);
        if (CollUtil.isEmpty(param.getFatherIds()) && CollUtil.isNotEmpty(param.getTagTypes())) {
            QueryWrapper<TagDto> preQueryWrapper = new QueryWrapper<>();
            preQueryWrapper.in(TagPo.COL_NAME, param.getTagTypes().stream().map(TagTypeEnum::getDescription).collect(Collectors.toList()));
            List<TagDto> fatherTags = list(preQueryWrapper);
            if (CollUtil.isEmpty(fatherTags)) {
                // 指定了标签类型，则一定要有父标签，否则会查出所有标签
                return Collections.emptyList();
            }
            param.setFatherIds(fatherTags.stream().map(TagDto::getId).collect(Collectors.toSet()));
        }
        List<TagDto> list = baseMapper.listRecursive(param);
        if (CollUtil.isEmpty(list)) {
            return Collections.emptyList();
        }
        Set<Integer> checkedIds = new HashSet<>();
        if (ObjectUtil.isNotNull(param.getSourceId())) {
            List<TagRelationDto> tagRelations = tagRelationService.listTagIds(List.of(param.getSourceId()));
            if (CollUtil.isNotEmpty(tagRelations)) {
                checkedIds.addAll(tagRelations.stream().map(TagRelationDto::getTagId).toList());
            }
        }
        List<TagDto> rootList = new ArrayList<>();
        Map<Integer, TagDto> map = list.stream().collect(Collectors.toMap(TagPo::getId, Function.identity()));
        list.forEach(item -> {
            if (checkedIds.contains(item.getId())) {
                item.setChecked(true);
            }
            if (Objects.isNull(item.getFatherId()) || CollUtil.contains(param.getFatherIds(), item.getFatherId())) {
                rootList.add(item);
            } else {
                TagDto parent = map.get(item.getFatherId());
                if (Objects.nonNull(parent)) {
                    if (Objects.isNull(parent.getChildren())) {
                        parent.setChildren(new ArrayList<>());
                    }
                    parent.getChildren().add(item);
                }
            }
        });
        rootList.sort(Comparator.comparingInt(TagDto::getOrderId));
        return rootList;
    }

    @Override
    public List<TagDto> listAntiRecursive(TagParam param) {
        List<TagDto> target = baseMapper.listByParam(param);
        if (target.size() != 1) {
            return Collections.emptyList();
        }
        List<TagDto> list = baseMapper.listAntiRecursive(param);
        Map<Integer, TagDto> map = list.stream()
                .collect(Collectors.toMap(TagPo::getId, Function.identity()));

        List<TagDto> result = new ArrayList<>();
        TagDto temp = target.getFirst();
        while (temp != null) {
            result.add(temp);
            temp = map.get(temp.getFatherId());
        }
        return result; // 返回从子到父的路径
    }


    @Override
    public TagDto listRecursiveByRootName(String name) {
        TagParam param = new TagParam();
        param.setName(name);
        param.setCreateBy(UserContext.getUserOnlineInfo().getUserName());
        List<TagDto> list = listRecursive(param);
        if (list.size() != 1) {
            return null;
        }
        TagDto root = list.get(0);
        sort(root);
        return root;
    }

    @Override
    public Integer findMaxOrder(Integer fatherId) {
        QueryWrapper<TagDto> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(TagPo.COL_FATHER_ID, fatherId);
        queryWrapper.orderBy(true, false, TagPo.COL_ORDER_ID);
        List<TagDto> list = list(queryWrapper);
        return CollUtil.isEmpty(list) ? 0 : list.get(0).getOrderId();
    }

    @Override
    public Boolean reorderTags(List<TagDto> list, Integer begin, Boolean isAsc) {
        int temp = begin;
        for (TagDto tagDto : list) {
            tagDto.setOrderId(BooleanUtil.isTrue(isAsc) ? temp++ : temp--);
        }
        return baseMapper.updateOrder(list);
    }

    @Override
    public Boolean removeRecursive(Integer id) {
        return baseMapper.removeRecursive(id);
    }

    @Override
    public Map<Integer, TagDto> getTagMap(TagParam param) {
        List<TagDto> list = baseMapper.listRecursive(param);
        if (CollUtil.isEmpty(list)) {
            return Collections.emptyMap();
        }
        return list.stream().collect(Collectors.toMap(TagPo::getId, Function.identity()));
    }

    @Override
    public Map<Integer, TagDto> getDailyWorkTagMap() {
        QueryWrapper<TagDto> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(TagPo.COL_NAME, DwConstant.DAILY_WORK);
        TagDto one = getOne(queryWrapper);
        TagParam param = new TagParam();
        param.setFatherIds(Collections.singleton(one.getId()));
        return getTagMap(param);
    }

    private void sort(TagDto current) {
        if (Objects.nonNull(current) && CollUtil.isNotEmpty(current.getChildren())) {
            List<TagDto> subTags = current.getChildren();
            subTags.sort(Comparator.comparingInt(TagDto::getOrderId));
            for (TagDto tag : subTags) {
                sort(tag);
            }
        }
    }
}
