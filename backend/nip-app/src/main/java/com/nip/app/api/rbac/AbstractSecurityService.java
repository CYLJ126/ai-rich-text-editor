package com.nip.app.api.rbac;

import com.nip.app.config.bean.WebSecurityProperties;
import com.nip.core.utils.crypto.Sm2Util;
import com.nip.core.utils.crypto.Sm2UtilForSmCrypto;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.springframework.beans.factory.annotation.Value;

/**
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2025/2/5 22:08 ✾
 */
@Slf4j
public abstract class AbstractSecurityService implements SecurityService {

    @Value("${security.sm2.privateKey}")
    protected String privateKeyText;

    @Value("${security.sm2.publicKey}")
    protected String publicKeyText;

    @Resource
    protected WebSecurityProperties webSecurityProperties;

    private ECPrivateKeyParameters privateKey;

    private ECPublicKeyParameters publicKey;

    @Override
    public String decrypt(String encoded) {
        return Sm2UtilForSmCrypto.decryptForSmCrypto(encoded, this.privateKey);
    }

    @Override
    public String encrypt(String decoded) {
        return Sm2UtilForSmCrypto.encryptForSmCrypto(decoded, this.publicKey);
    }

    @Override
    public String getPublicKey() {
        return publicKeyText;
    }

    @PostConstruct
    public void init() throws Exception {
        this.privateKey = Sm2Util.createPrivateKey(privateKeyText);
        this.publicKey = Sm2Util.createPublicKey(publicKeyText);
    }
}
