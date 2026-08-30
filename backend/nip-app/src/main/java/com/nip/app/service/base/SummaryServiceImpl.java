package com.nip.app.service.base;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.nip.app.api.base.SummaryService;
import com.nip.app.common.enums.SummaryOperationTypeEnum;
import com.nip.app.mapper.base.SummaryMapper;
import com.nip.app.pojo.base.SummaryDto;
import com.nip.app.pojo.base.SummaryPo;
import com.nip.core.utils.FormatUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * @author zhangsc
 * @since 2025/9/27 14:39
 */
@Slf4j
@Service
public class SummaryServiceImpl extends ServiceImpl<SummaryMapper, SummaryDto> implements SummaryService {

    @Override
    public SummaryDto getSummaryByTargetIdAndType(Integer id, String type) {
        QueryWrapper<SummaryDto> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(SummaryPo.COL_TYPE, type);
        queryWrapper.eq(SummaryPo.COL_TARGET_ID, id);
        return getOne(queryWrapper);
    }

    @Override
    public String formatContent(String text, SummaryOperationTypeEnum operationType) {
        return switch (operationType) {
            case FORMAT_SUMMARY -> formatSummary(text);
            case FORMAT_SERIAL_NO -> formatSerialNo(text);
            case REMOVE_TIME -> removeTime(text);
            case REMOVE_SUB -> removeSub(text);
        };
    }

    private String formatSummary(String text) {
        if (StrUtil.isEmpty(StrUtil.trim(text))) {
            return StrUtil.EMPTY;
        }
        String[] lines = text.split("\r?\n\r?\n");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String part = lines[i];
            if (StrUtil.isEmpty(StrUtil.trim(part))) {
                continue;
            }
            // 将第一个换行符前面和后面的信息分开

            String[] split = part.split("\r?\n", 2);
            String workName = FormatUtil.removeTime(split[0]);
            String workContent = FormatUtil.formatSummary(FormatUtil.removeSerialNo((split[1])));
            result.append(workName).append(workContent);
            if (i != lines.length - 1) {
                result.append(System.lineSeparator()).append(System.lineSeparator());
            }
        }
        return result.toString();
    }

    private String formatSerialNo(String text) {
        if (StrUtil.isEmpty(StrUtil.trim(text))) {
            return StrUtil.EMPTY;
        }
        String[] lines = text.split("\r?\n\r?\n");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String part = lines[i];
            if (StrUtil.isEmpty(StrUtil.trim(part))) {
                continue;
            }
            // 将第一个换行符前面和后面的信息分开

            String[] split = part.split("\r?\n", 2);
            String workName = split[0];
            String workContent = FormatUtil.formatSerialNo(FormatUtil.removeSerialNo(split[1]));
            result.append(workName).append(System.lineSeparator()).append(workContent);
            if (i != lines.length - 1) {
                result.append(System.lineSeparator()).append(System.lineSeparator());
            }
        }
        return result.toString();
    }

    private String removeTime(String text) {
        return FormatUtil.removeTime(text);
    }

    private String removeSub(String text) {
        return FormatUtil.removeSub(text, 2);
    }
}
