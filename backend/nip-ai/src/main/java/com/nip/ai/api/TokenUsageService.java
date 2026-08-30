package com.nip.ai.api;

import com.baomidou.mybatisplus.extension.service.IService;
import com.nip.ai.pojo.chat.TokenUsageDto;
import com.nip.core.pojo.PageView;

import java.time.LocalDate;
import java.util.List;

/**
 * TODO
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 18:03 ✾
 **/
public interface TokenUsageService extends IService<TokenUsageDto> {

    Boolean updateByConvId(TokenUsageDto dto);

    Boolean removeByConvId(String convId);

    Boolean removeByUserName(String userName);

    TokenUsageDto getByMessageId(String messageId);

    PageView<TokenUsageDto> pageByCondition(TokenUsageDto query, long pageNum, long pageSize);

    List<TokenUsageDto> listByCondition(TokenUsageDto query);

    List<TokenUsageDto> listByUserAndDateRange(String userName, LocalDate startDate, LocalDate endDate);
}
