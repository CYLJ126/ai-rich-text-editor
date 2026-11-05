package com.arte.ainew.pojo.model;

import com.arte.ainew.pojo.model.ModelResourceCommands.ModelKind;
import com.arte.core.utils.platform.PlatformChecks;

import java.util.Locale;

/**
 * 该列表使用授权模型的受管名称、所有权及状态。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/05 14:15 ✾
 */
public record ModelListFilter(String search, ModelKind modelKind, ModelResource.State state) {

    public static final ModelListFilter ALL = new ModelListFilter(null, null, null);

    public ModelListFilter {
        search = search == null ? "" : search.strip();
        PlatformChecks.require(search.length() <= 100 && search.chars().noneMatch(Character::isISOControl), "Invalid model search");
        search = search.toLowerCase(Locale.ROOT);
    }

    public boolean unfiltered() {
        return search.isEmpty() && modelKind == null && state == null;
    }

    public boolean matches(ModelResource model) {
        ModelKind kind = model.ownership().workspaceId() == null ? ModelKind.TENANT : ModelKind.PERSONAL;
        return (modelKind == null || modelKind == kind) && (state == null || state == model.state())
                && model.name().toLowerCase(Locale.ROOT).contains(search);
    }
}
