import {z} from 'zod';

/** 默认模板命令白名单；独立于传输模块，供持久恢复与请求校验共用。 */
const fixedRateSchema = z.object({
  type: z.literal('rate'), id: z.string().min(1).max(256),
  version: z.string().min(1).max(256).refine(value => value.toLowerCase() !== 'latest')
}).strict();
export const defaultAiPolicySchema = z.object({
  expectedCatalogVersion: z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER), enabled: z.boolean(),
  rateCaps: z.array(z.object({
    rate: fixedRateSchema, limit: z.object({
      amount: z.string().regex(/^[0-9]{1,18}(\.[0-9]{1,18})?$/), currency: z.string().regex(/^[A-Z]{3}$/)
    }).strict()
  }).strict()).max(100)
}).strict().refine(value => new Set(value.rateCaps.map(item => JSON.stringify(item.rate))).size === value.rateCaps.length);
export type DefaultAiPolicyCommand = z.infer<typeof defaultAiPolicySchema>;

