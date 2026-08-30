
package com.nip.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nip.ai.pojo.message.MessageDto;
import com.nip.core.annotations.MybatisParams;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

// TODO 由于 webFlux 为异步，故需添加请求拦截器，在 baseDto\baseParam 中添加当前用户信息
// TODO 或 mybatis 拦截器改从当前会话中的用户信息
@MybatisParams(value = "nip_ai_message", queryFields = {})
public interface MessageMapper extends BaseMapper<MessageDto> {

    /**
     * 查询主分支消息（用于上下文构建）
     * 按 sort_order 升序，取最新 limit 条
     */
    List<MessageDto> selectMainBranchMessages(@Param("convId") String convId, @Param("limit") int limit);

    /**
     * 查询指定消息之前的消息（用于重新生成时重建当时的上下文）
     */
    List<MessageDto> selectMessagesBefore(@Param("convId") String convId,
                                          @Param("beforeId") int beforeId,
                                          @Param("limit") int limit);

    /**
     * 查询提供给模型的会话上下文。
     * 排除 role=tool 和携带 tool_calls 的中间 assistant 协议消息。
     */
    List<MessageDto> selectConversationContextMessages(@Param("convId") String convId,
                                                       @Param("limit") int limit);

    /**
     * 查询指定消息之前、提供给模型的会话上下文。
     */
    List<MessageDto> selectConversationContextMessagesBefore(@Param("convId") String convId,
                                                             @Param("beforeId") int beforeId,
                                                             @Param("limit") int limit);

    /**
     * 批量软删除消息
     */
    void batchSoftDelete(@Param("messageIds") Collection<String> messageIds, @Param("updateBy") String updateBy);
}
