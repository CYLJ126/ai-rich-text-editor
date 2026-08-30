/**
 * 判断对象是否为空对象
 * @param obj
 */
export function isEmpty(obj: any) {
  return obj != null &&
    typeof obj === 'object' &&
    !Array.isArray(obj) &&
    Object.entries(obj).length === 0;
}

/**
 * 判断对象是否为空对象，包括子对象
 * 使用 Reflect.ownKeys() 检测包括 不可枚举属性 和 Symbol 属性 在内的完全空对象
 * @param obj
 */
export function isDeepEmpty(obj: any) {
  if (obj == null || typeof obj !== 'object' || Array.isArray(obj)) {
    return false;
  }
  return Reflect.ownKeys(obj).length === 0;
}

/**
 * 将对象 a 的属性复制到对象 b 中，并返回新对象
 * 只复制 a 和 b 中相同的属性，必须强制类型兼容
 * @param a 源对象
 * @param b 目标对象
 * @param keys 要复制的属性名数组， 必须满足 B 的子集类型
 */
export function copySameKeysImmutable<
  B extends Record<string, any>,
  K extends keyof B,
  A extends { [P in K]: B[P] },
>(a: A, b: B, keys: readonly K[]): B {
  const next = {...b};
  keys.forEach(key => {
    (next as any)[key] = a[key];
  });
  return next;
}

/**
 * 将对象 a 的属性复制到对象 b 中，并返回新对象
 * 只复制 a 和 b 中相同的属性，忽略类型兼容
 * @param a 源对象
 * @param b 目标对象
 * @param keys 要复制的属性名数组， 必须满足 B 的子集类型
 */
export function copySameKeysIgnoreTypeCheck<
  A extends Record<string, any>,
  B extends Record<string, any>,
>(a: A, b: B, keys: readonly string[]): B {
  const next = {...b};
  keys.forEach(key => {
    if (key in a) {
      (next as any)[key] = (a as any)[key];
    }
  });
  return next;
}
