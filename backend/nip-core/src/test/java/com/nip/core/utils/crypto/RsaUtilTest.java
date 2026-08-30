package com.nip.core.utils.crypto;

import cn.hutool.core.text.CharSequenceUtil;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.data.util.Pair;

@Slf4j
class RsaUtilTest {

    static final String privateKey = "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDcKonsU9k0BWyPh4RtY/wbmrcGNfnY2w5aiCvaf0yDU/23fDX+poiIHA1cAiSpbpsYL7sjhjZFKHXHADkt5qQKR9sEvLpjAWdIYeplTAmxvNT1FHo9NWzpupRYggNqnVllXs2DeLHtHtU+e8sFRdfM81Eo5FiAqZMtX77zclg+BuajRnuxIIzQtg1ge+jpCw1c1WNyGoJ4W+2xgySvBlLuJKnR6Z3qsebR6AMtYAEOdUthO5SUU8DQ4Y8lY0qTX3sc+9x5Oe6ybJNRqg6zBiJkGANqUonPVZIsoxz/d4/oCT2n11moUbNzXkQrUy+M+H5X7BXY590oQKP7wpEFj5UbAgMBAAECggEAEJvk678nk5UVK369rsLsMejqBb3sqz9bRdkf9cvEqeOcMEteh2doxVvYZiiDO4TsjlaLd5KYZX8341tQ9PWBSoVBMIpMRUvFzuAUHrAtywrDxflVf2iYXsmEh2jHeTOFmvAb8N8aDRlWXPBZ0HwEW6G/baZMRuiEAdhHP3ZQ0dzvlxEs353l/U2zXXl75JTBeb4OpBpHK7Bu7CDmGxwQQaLnMXtFPvR8RcG7AknpXFzNYhB3LCwS41SDBHADoYG2jSmbx5HNtiT78usUlmKzSoeE+3UmfZeNNuzVCPUg5vm7Cdt/C+wk2OkYiyf9Pwf1qCh4U3MwibF8+8oj91q+gQKBgQDreSKCCxEQ06TnSiKEAopNoKP8Tn7Ko8hyoxGTIxRTsjJ7+fz7XGOUR0e88mymd0tScfeDZatPjWISsaMbh+VhLMvonKRJPWZgesVxNIulKBCgodoMBZAK0/IkJdg+mcLTX8ai/7ScTY79J6ap9OSwZcCEAhlxFXp9D8uP7hnZxwKBgQDvW89EK7dgEZZ/0Bd1kfboaIpxzCf5zLnepuSchgibG3jDkjRdQz0r5rDN5Ii0kd+7r5WbtlxFYpjXHxbBm1sHxRke4a46K/p90mL73WBOJ5Xo16/mCNZeAFow4wmuTBFJyPg6AK5y2N4dXsdtNGPsl/aFn4fLgoOHKo91AFRKDQKBgH6aLneEw7QsVqkULuKTCxEZoZFyErGYxZj9G+HkJnWphYMqsV3kGpYKjQOFw+zsjs7Q/6bh7u1isqwiXfPbaPSKRUfXkGzgWj7dQ3LOMZamp+n1m2qDQyLPOY0927osqZdEE2Rn3w96k7qAqxcN2DcPPia7ijpGtgiviV7lQXjZAoGAZBnxRIFD31l7p1hPd0vkBq4xuAW5ci68TPFfClbDopqKlr++RoKoQoPnzTOWOG4JM2TrHIPXcu0ZHl5SFgXyu/0h1FhtlMZvq88gtlSOwiCsAQSy+3EWtZlQHsHUeqA1WrZBmYBPdJhdy8qnfHQ8leW3o4pkMCgU+aJC/Jt2pMECgYEA1BdOxzy2x5h9DZtr/ryWen2kGFisFIAdOQnx7Nx/oB6uzNKZ/yfDQ5ByykrpP3Tf18VaTvmCIP6M+n1NxdViOgjU4HUBXoL1ROTebao5EfN+am5mI+tqmmaGWGqW93vBxPp4jBnpf5k/SwRmCK9lmBapkfpBQIHtvQXyS+Jpl2M=";
    static final String publicKey = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA3CqJ7FPZNAVsj4eEbWP8G5q3BjX52NsOWogr2n9Mg1P9t3w1/qaIiBwNXAIkqW6bGC+7I4Y2RSh1xwA5LeakCkfbBLy6YwFnSGHqZUwJsbzU9RR6PTVs6bqUWIIDap1ZZV7Ng3ix7R7VPnvLBUXXzPNRKORYgKmTLV++83JYPgbmo0Z7sSCM0LYNYHvo6QsNXNVjchqCeFvtsYMkrwZS7iSp0emd6rHm0egDLWABDnVLYTuUlFPA0OGPJWNKk197HPvceTnusmyTUaoOswYiZBgDalKJz1WSLKMc/3eP6Ak9p9dZqFGzc15EK1MvjPh+V+wV2OfdKECj+8KRBY+VGwIDAQAB";

    @Test
    void generateKeyPair() throws Exception {
        String text = "测试";
        Pair<String, String> pair = RsaUtil.generateKeyPair();
        log.info("私钥：【{}】", pair.getSecond());
        log.info("公钥：【{}】", pair.getFirst());
        String encoded = RsaUtil.encrypt(pair.getFirst(), text);
        log.info("加密内容：{}", encoded);
        String decoded = RsaUtil.decrypt(pair.getSecond(), encoded);
        log.info("解密内容：{}", decoded);
        assert CharSequenceUtil.equals(text, decoded);

    }

    @Test
    void encrypt() throws Exception {
        String text = "Aa111111";
        String encoded = RsaUtil.encrypt(publicKey, text);
        log.info("加密内容：{}", encoded);
        String decoded = RsaUtil.decrypt(privateKey, encoded);
        log.info("解密内容：{}", decoded);
        assert CharSequenceUtil.equals(text, decoded);

    }
}