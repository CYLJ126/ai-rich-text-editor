package com.nip.ai.web.controller;

import com.nip.ai.common.enums.*;
import com.nip.core.annotations.AnonymousAccess;
import com.nip.core.enums.MyEnum;
import com.nip.core.enums.TextTypeEnum;
import com.nip.core.pojo.DropdownDto;
import com.nip.core.pojo.ResultContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 元信息接口
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/7/15 09:51 ✾
 **/
@RestController
@RequestMapping("/ai/metaInfo")
@RequiredArgsConstructor
public class MetaInfoController {

    /**
     * 查询模型提供商列表
     */
    @GetMapping("/listModelProviders")
    @AnonymousAccess
    public ResultContext<List<DropdownDto>> listModelProviders() {
        return ResultContext.success(MyEnum.getDropdownOptions(ModelProviderEnum.class));
    }

    /**
     * 查询模型类型列表
     */
    @GetMapping("/listModelTypes")
    @AnonymousAccess
    public ResultContext<List<DropdownDto>> listModelTypes() {
        return ResultContext.success(MyEnum.getDropdownOptions(ModelTypeEnum.class));
    }

    /**
     * 查询上下文策略类型列表
     */
    @GetMapping("/listContextStrategies")
    @AnonymousAccess
    public ResultContext<List<DropdownDto>> listContextStrategies() {
        return ResultContext.success(MyEnum.getDropdownOptions(ContextStrategyEnum.class));
    }

    /**
     * 查询知识库类型列表
     */
    @GetMapping("/listKnowledgeBaseTypes")
    @AnonymousAccess
    public ResultContext<List<DropdownDto>> listKnowledgeBaseTypes() {
        return ResultContext.success(MyEnum.getDropdownOptions(KnowledgeBaseTypeEnum.class));
    }

    /**
     * 查询文本格式列表
     */
    @GetMapping("/listTextTypes")
    @AnonymousAccess
    public ResultContext<List<DropdownDto>> listTextTypes() {
        return ResultContext.success(MyEnum.getDropdownOptions(TextTypeEnum.class));
    }

    /**
     * 查询推理力度列表
     */
    @GetMapping("/listReasoningEfforts")
    @AnonymousAccess
    public ResultContext<List<DropdownDto>> listReasoningEfforts() {
        return ResultContext.success(MyEnum.getDropdownOptions(ReasoningEffortEnum.class));
    }

}
