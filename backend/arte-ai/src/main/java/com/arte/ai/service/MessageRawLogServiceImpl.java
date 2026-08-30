package com.arte.ai.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.arte.ai.api.MessageRawLogService;
import com.arte.ai.mapper.MessageRawLogMapper;
import com.arte.ai.pojo.message.MessageRawLogDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/7/17 22:31 ✾
 **/
@Slf4j
@Service
public class MessageRawLogServiceImpl extends ServiceImpl<MessageRawLogMapper, MessageRawLogDto> implements MessageRawLogService {
}
