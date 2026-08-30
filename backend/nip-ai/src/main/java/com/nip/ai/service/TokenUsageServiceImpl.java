package com.nip.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.nip.ai.api.TokenUsageService;
import com.nip.ai.mapper.TokenUsageMapper;
import com.nip.ai.pojo.chat.TokenUsageDto;
import com.nip.core.pojo.PageView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Token 用量 Service 实现
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 18:03 ✾
 **/
@Slf4j
@Service
public class TokenUsageServiceImpl extends ServiceImpl<TokenUsageMapper, TokenUsageDto> implements TokenUsageService {

    /**
     * 根据会话 ID 更新 Token 用量记录
     *
     * @param dto 含 convId 的 DTO
     * @return 是否成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateByConvId(TokenUsageDto dto) {
        Objects.requireNonNull(dto, "TokenUsageDto must not be null");
        if (!StringUtils.hasText(dto.getConvId())) {
            throw new IllegalArgumentException("convId must not be blank");
        }
        LambdaUpdateWrapper<TokenUsageDto> wrapper = new LambdaUpdateWrapper<TokenUsageDto>()
                .eq(TokenUsageDto::getConvId, dto.getConvId())
                .set(Objects.nonNull(dto.getInputTokens()),
                        TokenUsageDto::getInputTokens, dto.getInputTokens())
                .set(Objects.nonNull(dto.getOutputTokens()),
                        TokenUsageDto::getOutputTokens, dto.getOutputTokens())
                .set(Objects.nonNull(dto.getReasoningTokens()),
                        TokenUsageDto::getReasoningTokens, dto.getReasoningTokens())
                .set(Objects.nonNull(dto.getTotalTokens()),
                        TokenUsageDto::getTotalTokens, dto.getTotalTokens());
        log.debug("[TokenUsage] updateByConvId, convId={}", dto.getConvId());
        return update(wrapper);
    }

    /**
     * 根据会话 ID 删除 Token 用量记录
     *
     * @param convId 会话 ID
     * @return 是否成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeByConvId(String convId) {
        if (!StringUtils.hasText(convId)) {
            throw new IllegalArgumentException("convId must not be blank");
        }
        LambdaQueryWrapper<TokenUsageDto> wrapper = new LambdaQueryWrapper<TokenUsageDto>()
                .eq(TokenUsageDto::getConvId, convId);
        log.debug("[TokenUsage] removeByConvId, convId={}", convId);
        return remove(wrapper);
    }

    /**
     * 根据用户名删除 Token 用量记录（慎用）
     *
     * @param userName 用户名
     * @return 是否成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeByUserName(String userName) {
        if (!StringUtils.hasText(userName)) {
            throw new IllegalArgumentException("userName must not be blank");
        }
        LambdaQueryWrapper<TokenUsageDto> wrapper = new LambdaQueryWrapper<TokenUsageDto>()
                .eq(TokenUsageDto::getUserName, userName);
        log.debug("[TokenUsage] removeByUserName, userName={}", userName);
        return remove(wrapper);
    }

    /**
     * 根据消息 ID 查询单条记录
     *
     * @param messageId 消息 ID
     * @return TokenUsageDto
     */
    @Override
    public TokenUsageDto getByMessageId(String messageId) {
        if (!StringUtils.hasText(messageId)) {
            throw new IllegalArgumentException("messageId must not be blank");
        }
        return getOne(new LambdaQueryWrapper<TokenUsageDto>()
                .eq(TokenUsageDto::getMessageId, messageId)
                .last("LIMIT 1"));
    }

    /**
     * 按条件分页查询 Token 用量记录
     *
     * @param query    查询条件（利用 DTO 中 startDate/endDate/userName/modelId/convId）
     * @param pageNum  页码（从 1 开始）
     * @param pageSize 每页大小
     * @return 分页结果
     */
    @Override
    public PageView<TokenUsageDto> pageByCondition(TokenUsageDto query, long pageNum, long pageSize) {
        Objects.requireNonNull(query, "query condition must not be null");
        LambdaQueryWrapper<TokenUsageDto> wrapper = buildQueryWrapper(query);
        wrapper.orderByDesc(TokenUsageDto::getCreateTime);
        Page<TokenUsageDto> page = new Page<>(pageNum, pageSize);
        log.debug("[TokenUsage] pageByCondition, pageNum={}, pageSize={}", pageNum, pageSize);
        Page<TokenUsageDto> pageResult = page(page, wrapper);
        return PageView.success(pageResult);
    }

    /**
     * 按条件查询 Token 用量列表（不分页）
     *
     * @param query 查询条件
     * @return List<TokenUsageDto>
     */
    @Override
    public List<TokenUsageDto> listByCondition(TokenUsageDto query) {
        Objects.requireNonNull(query, "query condition must not be null");
        LambdaQueryWrapper<TokenUsageDto> wrapper = buildQueryWrapper(query);
        wrapper.orderByDesc(TokenUsageDto::getCreateTime);
        return list(wrapper);
    }

    /**
     * 按用户名查询某段时间内的 Token 用量列表
     *
     * @param userName  用户名
     * @param startDate 起始日期（含）
     * @param endDate   结束日期（含）
     * @return List<TokenUsageDto>
     */
    @Override
    public List<TokenUsageDto> listByUserAndDateRange(String userName, LocalDate startDate, LocalDate endDate) {
        LambdaQueryWrapper<TokenUsageDto> wrapper = new LambdaQueryWrapper<TokenUsageDto>()
                .eq(StringUtils.hasText(userName), TokenUsageDto::getUserName, userName)
                .ge(Objects.nonNull(startDate), TokenUsageDto::getUsageDate, startDate)
                .le(Objects.nonNull(endDate), TokenUsageDto::getUsageDate, endDate)
                .orderByAsc(TokenUsageDto::getUsageDate);
        return list(wrapper);
    }

    // ======================== 私有工具方法 ========================

    /**
     * 构建通用查询条件
     */
    private LambdaQueryWrapper<TokenUsageDto> buildQueryWrapper(TokenUsageDto query) {
        return new LambdaQueryWrapper<TokenUsageDto>()
                // 精确匹配
                .eq(StringUtils.hasText(query.getUserName()),
                        TokenUsageDto::getUserName, query.getUserName())
                .eq(Objects.nonNull(query.getModelId()),
                        TokenUsageDto::getModelId, query.getModelId())
                .eq(StringUtils.hasText(query.getConvId()),
                        TokenUsageDto::getConvId, query.getConvId())
                .eq(StringUtils.hasText(query.getMessageId()),
                        TokenUsageDto::getMessageId, query.getMessageId())
                // 日期范围
                .ge(Objects.nonNull(query.getStartDate()),
                        TokenUsageDto::getUsageDate, query.getStartDate())
                .le(Objects.nonNull(query.getEndDate()),
                        TokenUsageDto::getUsageDate, query.getEndDate());
    }
}
