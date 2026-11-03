package com.arte.app.platform.ai;

import com.arte.ainew.pojo.budget.Money;
import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.utils.platform.PlatformChecks;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * 平台审核的文本模型与费率目标值
 * <p>
 * 不接受凭据、授权或客户端生成的发布引用。
 *
 * @param id                  稳定方案标识，同标识修改追加固定版本。
 * @param connection          已发布的精确连接版本。
 * @param remoteModel         供应商实际模型名，不从文件种子推断。
 * @param contextWindowTokens 审核的上下文容量，个人草稿不能超过此上限。
 * @param currency            ISO 币种，输入输出价格使用同一币种。
 * @param inputPerMillion     每百万输入 Token 价格，使用十进制字符串。
 * @param outputPerMillion    每百万输出 Token 价格，使用十进制字符串。
 * @param toolCalls           是否明确声明支持工具调用，仍须网关适配支持
 * @param usageReporting      是否声明供应商支持返回用量，登记不构成探测证据。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/01 14:45 ✾
 */
public record ReviewedModelSettings(String id, DefinitionRef connection, String remoteModel,
                                    long contextWindowTokens, String currency, String inputPerMillion,
                                    String outputPerMillion, boolean usageReporting, boolean toolCalls) {

    /**
     * 受信内部调用可省略工具能力，默认不声明支持。
     */
    public ReviewedModelSettings(String id, DefinitionRef connection, String remoteModel, long contextWindowTokens,
                                 String currency, String inputPerMillion, String outputPerMillion, boolean usageReporting) {
        this(id, connection, remoteModel, contextWindowTokens, currency, inputPerMillion, outputPerMillion, usageReporting, false);
    }

    public ReviewedModelSettings {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")) {
            throw new IllegalArgumentException("Invalid model plan id");
        }
        Objects.requireNonNull(connection).requireType("connection");
        if ("latest".equalsIgnoreCase(connection.version())) {
            throw new IllegalArgumentException("Connection requires a fixed version");
        }
        PlatformChecks.id(remoteModel, "remoteModel");
        if (contextWindowTokens < 2 || contextWindowTokens > 10_000_000) {
            throw new IllegalArgumentException("Invalid model context capacity");
        }
        Currency unit = Currency.getInstance(Objects.requireNonNull(currency));
        inputPerMillion = price(inputPerMillion, unit);
        outputPerMillion = price(outputPerMillion, unit);
    }

    private static String price(String value, Currency currency) {
        if (value == null || !value.matches("[0-9]{1,18}(\\.[0-9]{1,18})?")) {
            throw new IllegalArgumentException("Invalid decimal model price");
        }
        return new Money(new BigDecimal(value), currency).amount().toPlainString();
    }
}
