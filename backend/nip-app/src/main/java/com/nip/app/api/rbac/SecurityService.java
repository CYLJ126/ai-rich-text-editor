package com.nip.app.api.rbac;

/**
 * 安全认证相关基础接口
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2025/2/5 22:07 ✾
 */
public interface SecurityService {

    /**
     * 使用默认密钥解密信息
     *
     * @param encoded 密文
     * @return 解密信息
     * @throws Exception 解密异常
     */
    String decrypt(String encoded) throws Exception;

    /**
     * 使用默认密钥加密信息
     *
     * @param decoded 明文
     * @return 加密信息
     * @throws Exception 加密异常
     */
    String encrypt(String decoded) throws Exception;

    String getPublicKey();
}
