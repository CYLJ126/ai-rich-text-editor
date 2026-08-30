package com.nip.ai.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.nip.ai.api.MessageRawLogService;
import com.nip.ai.mapper.MessageRawLogMapper;
import com.nip.ai.pojo.message.MessageRawLogDto;
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
