package com.arte.ainew.pojo.execution;

import com.arte.ainew.common.validation.ContractChecks;

import java.io.Serializable;

/**
 * 固定结果及其已校验内容摘要；partial 表示仅保留部分输出，不能宣称完整成功。持有引用不获得访问权。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public record ResultRef(String resultId, String resultType, int schemaVersion,
                        String contentDigest, boolean partial) implements Serializable {
    public ResultRef {
        ContractChecks.id(resultId, "resultId");
        ContractChecks.id(resultType, "resultType");
        ContractChecks.range(schemaVersion, "schemaVersion", 1, Integer.MAX_VALUE);
        ContractChecks.digest(contentDigest, "contentDigest");
    }
}
