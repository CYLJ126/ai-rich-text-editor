package com.arte.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.arte.ai.pojo.message.MessageAttachmentDto;
import com.arte.core.annotations.MybatisParams;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

@MybatisParams("arte_ai_message_attachment")
public interface MessageAttachmentMapper extends BaseMapper<MessageAttachmentDto> {
    // 流式处理线程不携带 UserContext，使用请求中的用户和会话条件。
    @MybatisParams(ignore = true)
    @Select("""
            <script>
            SELECT * FROM arte_ai_message_attachment
            WHERE conv_id = #{convId} AND create_by = #{userName} AND attach_type = 'IMAGE'
              AND message_id IN
            <foreach collection="messageIds" item="messageId" open="(" separator="," close=")">
                #{messageId}
            </foreach>
            ORDER BY id
            </script>
            """)
    List<MessageAttachmentDto> listImages(@Param("convId") String convId,
                                          @Param("messageIds") Collection<String> messageIds,
                                          @Param("userName") String userName);
}
