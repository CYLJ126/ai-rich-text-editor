package com.nip.ai.api;

import com.nip.ai.pojo.FrontendSaveRequestDto;

/**
 * 前端模式
 * 前端直接与大模型交互，事后保存到数据库
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/29 13:38 ✾
 **/
public interface FrontEndChatService {

    void saveMessage(FrontendSaveRequestDto saveRequest);
}
