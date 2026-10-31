package com.arte.ainew.spi.model;

import com.arte.ainew.pojo.model.ModelDrafts.Credential;

/**
 * 个人模型凭据的加密及带密钥指纹端口，由宿主适配部署密钥管理方式。
 * <p>
 * 加密须认证调用方传入的业务上下文，阻止密文跨模型、空间或凭据代次替换；解密失败时不得返回明文。
 * 加密密钥与稳定指纹密钥可独立选择，轮换加密密钥不能隐式改变已保存命令的幂等依据。
 * 此端口只处理密码学操作，不判断成员、模型或凭据维护资格，调用前须由应用服务完成授权。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 15:55 ✾
 */
public interface ModelCredentialCipher {

    /**
     * 返回本次新密文使用的加密密钥标识，供凭据记录保存并在后续解密时精确定位密钥。
     *
     * @return 当前写入密钥的标识，不包含密钥内容
     */
    String activeKeyId();

    /**
     * 使用当前密钥及随机 nonce 加密明文，同时认证指定业务上下文。
     *
     * @param plaintext      待加密的个人访问密钥，只能在受控服务端内存中短暂使用
     * @param associatedData 由服务端构建的业务绑定信息，包含模型范围、责任主体及凭据代次的关联依据
     * @return 可持久化的版本化密文封装，包含解密所需 nonce 和认证标签，不包含明文密钥
     */
    String encrypt(String plaintext, String associatedData);

    /**
     * 按凭据记录的密钥标识执行认证解密；已撤销、格式不受支持、密钥缺失或认证失败时拒绝。
     *
     * @param credential     经当前用途授权确认的内部凭据记录，不能直接使用客户端自报记录
     * @param associatedData 按当前资源、责任主体及代次重新构建的绑定信息，须与加密时一致
     * @return 仅供本次授权用途使用的明文密钥，不得写入 HTTP 响应、日志或持久化记录
     */
    String decrypt(Credential credential, String associatedData);

    /**
     * 使用稳定指纹密钥计算确定性摘要，供敏感字段的幂等核对及有明确用途区分的游标签名使用。
     * 不保存可独立离线猜测的普通密钥哈希；指纹本身也不授予任何访问资格。
     *
     * @param plaintext 待计算指纹的敏感值或已区分业务用途的签名输入，不得记录原值
     * @return 在相同指纹密钥及输入下稳定的带密钥摘要
     */
    String fingerprint(String plaintext);
}
