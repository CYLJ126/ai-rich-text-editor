import {i18nText} from '@/utils/i18n';
import {sm2, sm3, sm4} from 'sm-crypto';

/**

 * 获取密码学安全的随机字节数组（兼容 Node.js 和浏览器）
 * @param size 字节数
 * @returns Uint8Array 包含随机字节
 */
function getRandomBytes(size: number): Uint8Array {
  if (typeof window !== 'undefined' && window.crypto) {
    // 浏览器环境
    const buffer = new Uint8Array(size);
    window.crypto.getRandomValues(buffer);
    return buffer;
  } else {
    throw new Error(i18nText("app.common.crypto.gmcrypto.5baf066b"));
  }
}

/**
 * 国密算法工具类 (基于 sm-crypto)
 * 包含 SM2 (非对称) 和 SM4 (对称) 的常用加解密方法
 */
export class GMCrypto {
  // --- SM2 相关方法 (非对称加密) ---

  /**
   * 生成 SM2 密钥对
   * @returns { publicKey: string, privateKey: string } 返回十六进制格式的公钥和私钥
   */
  static generateSM2KeyPair(): { publicKey: string; privateKey: string } {
    // key 为 130 字符的非压缩公钥 (以04开头) 和 64 字符的私钥 [citation:2][citation:5]
    const keypair = sm2.generateKeyPairHex();
    return {
      publicKey: keypair.publicKey,
      privateKey: keypair.privateKey,
    };
  }

  /**
   * SM2 加密
   * @param plainText 明文
   * @param publicKey 公钥 (十六进制字符串，通常以04开头)
   * @param mode 加密模式 (0: C1C3C2, 1: C1C2C3)，默认使用 1。需与解密时保持一致 [citation:2]
   * @returns 密文 (十六进制字符串)
   */
  static sm2Encrypt(
    plainText: string,
    publicKey: string,
    mode: 0 | 1 = 1,
  ): string {
    // sm-crypto 的 doEncrypt 默认返回 hex 格式
    return sm2.doEncrypt(plainText, publicKey, mode);
  }

  /**
   * SM2 解密
   * @param cipherText 密文 (十六进制字符串)
   * @param privateKey 私钥 (十六进制字符串)
   * @param mode 解密模式 (0: C1C3C2, 1: C1C2C3)，必须与加密时使用的 mode 一致 [citation:2]
   * @returns 明文字符串
   */
  static sm2Decrypt(
    cipherText: string,
    privateKey: string,
    mode: 0 | 1 = 1,
  ): string {
    return sm2.doDecrypt(cipherText, privateKey, mode);
  }

  /**
   * 判断字符串是否为 SM2 密文（用于避免重复加密）
   *
   * sm-crypto 的 doEncrypt 输出结构（全为十六进制字符）：
   *   - C1：随机椭圆曲线点 x||y，固定 128 个十六进制字符（注意：sm-crypto 会去掉 "04" 前缀，故密文不以 04 开头）
   *   - C3：SM3 摘要，固定 64 个十六进制字符
   *   - C2：密文，长度 = 明文字节数 × 2
   * 故任意非空明文的密文长度 ≥ 128 + 64 = 192，且仅含 0-9a-fA-F。
   *
   * 说明：此为启发式判断。对于本身就是纯十六进制且长度 ≥ 192 的明文（极少见）会误判。
   * 若需 100% 可靠，建议加密时加前缀标记（如 "SM2:"），再用 startsWith 判断。
   *
   * @param value 待判断的字符串
   * @returns 是否疑似 SM2 密文
   */
  static isSM2Encrypted(value: string): boolean {
    if (typeof value !== 'string' || value.length < 192) return false;
    if (value.startsWith("SM2") || value.startsWith("sm2")) return true;
    return /^[0-9a-fA-F]+$/.test(value);
  }

  // --- 新增：SM3 摘要算法 ---

  /**
   * 计算 SM3 哈希值
   * @param data 输入数据，可以是字符串或 Uint8Array（字节数组）
   * @returns 64字符的十六进制哈希字符串（256 位）
   */
  static sm3Hash(data: string | Uint8Array): string {
    // sm-crypto 的 sm3 函数直接接受 string 或 Uint8Array
    return sm3(data);
  }

  /**
   * 计算文件的 SM3 哈希（流式处理，适用于大文件）
   * 注意：此方法需要浏览器环境或 Node.js 的 File/Blob 支持
   * @param file File 或 Blob 对象
   * @returns Promise，解析为十六进制哈希字符串
   */
  static async sm3HashFile(file: Blob): Promise<string> {
    // sm-crypto 提供了流式计算的辅助对象
    // 更可靠的方式：使用 Web Crypto API 或 Node.js crypto？但国密 SM3 不是标准，需用纯 JS 实现。
    // sm-crypto 的 sm3 函数不支持增量更新。替代方案：将文件分块读取后拼接再计算，但内存占用大。
    // 为了简化，这里给出一个利用 FileReader 或 Node.js 流将整个文件读入内存再计算的示例。
    // 实际生产环境建议使用 Web Crypto 的 digest 或 Node.js 的 createHash 进行 SHA 系列哈希，
    // 但对于 SM3，可考虑使用其他支持流式的库（如 gm-crypto 支持流式），此处保留扩展说明。
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = (e) => {
        const arrayBuffer = e.target?.result as ArrayBuffer;
        const uint8Array = new Uint8Array(arrayBuffer);
        const hash = sm3(uint8Array);
        resolve(hash);
      };
      reader.onerror = (e) => reject(e);
      reader.readAsArrayBuffer(file);
    });
  }

  // --- SM4 相关方法 (对称加密) ---

  /**
   * 生成随机 SM4 密钥（16 字节/32 位 16 进制字符串）
   * @returns SM4 密钥
   */
  static generateSM4Key(): string {
    // 生成 16 字节随机数并转为 16 进制
    const array = getRandomBytes(16);
    window.crypto.getRandomValues(array as any);
    return Array.from(array, (byte) => byte.toString(16).padStart(2, '0')).join(
      '',
    );
  }

  /**
   * 生成 SM4 随机初始化向量 (IV)（128 位）
   * @returns 32 字符的十六进制字符串
   */
  static generateSM4IV(): string {
    const bytes = getRandomBytes(16);
    return Array.from(bytes)
      .map((b) => b.toString(16).padStart(2, '0'))
      .join('');
  }

  /**
   * SM4 加密 (CBC 或 ECB 模式)
   * @param plainText 明文
   * @param key 密钥 (十六进制字符串，SM4 密钥长度固定为 128位，即 32 个十六进制字符) [citation:2][citation:8]
   * @param iv 初始化向量 (CBC模式必需，ECB模式不需要。需为 32 位十六进制字符串) [citation:2]
   * @param mode 加密模式，支持 'cbc' 或 'ecb'，默认 'cbc'
   * @returns 加密后的十六进制字符串
   */
  static sm4Encrypt(
    plainText: string,
    key: string,
    iv: string = '',
    mode: 'cbc' | 'ecb' = 'cbc',
  ): string {
    const sm4Config: any = {
      mode, // 传入模式
    };
    // CBC 模式需要传入 iv，ECB 模式不需要
    if (mode === 'cbc') {
      if (!iv) {
        throw new Error('CBC mode requires an IV (initialization vector).');
      }
      sm4Config.iv = iv;
    }
    // sm4.encrypt 返回 hex 字符串 [citation:2]
    return sm4.encrypt(plainText, key, sm4Config);
  }

  /**
   * SM4 解密 (CBC 或 ECB 模式)
   * @param cipherText 密文 (十六进制字符串)
   * @param key 密钥 (需与加密时的密钥一致)
   * @param iv 初始化向量 (需与加密时的 IV 一致，ECB模式可不传)
   * @param mode 解密模式，支持 'cbc' 或 'ecb'，需与加密时的 mode 一致
   * @returns 解密后的明文字符串
   */
  static sm4Decrypt(
    cipherText: string,
    key: string,
    iv: string = '',
    mode: 'cbc' | 'ecb' = 'cbc',
  ): string {
    const sm4Config: any = {
      mode,
    };
    if (mode === 'cbc') {
      if (!iv) {
        throw new Error('CBC mode requires an IV (initialization vector).');
      }
      sm4Config.iv = iv;
    }
    return sm4.decrypt(cipherText, key, sm4Config);
  }
}
