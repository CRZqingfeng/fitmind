package com.fitmind.promotion.utils;

import com.fitmind.common.exceptions.BadRequestException;

/**
 * ★★★ 兑换码算法（课件里的「20 亿量级兑换码」）。
 *
 * <h3>要解决的问题</h3>
 * <p>运营要发 20 亿个兑换码。如果每个码在数据库里存一行，
 * 光这张表就有 20 亿条记录 —— 存不下、查不动、也没必要。</p>
 *
 * <h3>核心思路：码不存，靠算</h3>
 * <p>兑换码<b>不是随机串</b>，而是"序列号"的可逆变换：</p>
 * <pre>
 *   序列号（32位，全局自增，Redis 发号）
 *     + 新鲜值（4位，= 券id 的低4位，让同一张券的码有相同"标记"）
 *     = 载荷 payload（36位）
 *     → 加权求和算校验码（14位）
 *     → payload 与一个大质数异或（混淆，防止看出连续规律）
 *     → 明文 = 校验码(14) + 混淆后的payload(36) = 50 位
 *     → Base32 编码 = 10 个字符
 * </pre>
 *
 * <h3>三个组成部分各自解决什么</h3>
 * <ol>
 *   <li><b>校验码（14位）</b>：解码时重新算一遍比对。
 *       <b>不用查库就能识别出伪造的码</b> —— 这是它最实际的价值：
 *       别人瞎编一个 10 位串，第一步校验就被打回，连 Redis 都不用访问</li>
 *   <li><b>新鲜值（4位）</b>：同一张券的码共享同一个值，
 *       从码上就能看出"这批码属于同一张券"（虽然券id只有4位，会重复，但够用于分组）</li>
 *   <li><b>异或混淆</b>：如果不混淆，相邻序号的码长得很像（只差一两位），
 *       用户领了两个码就能猜出规律、进而伪造。异或之后看不出递增关系</li>
 * </ol>
 *
 * <h3>存储怎么省下来的</h3>
 * <ul>
 *   <li><b>码本身</b>：不存，用的时候现算</li>
 *   <li><b>是否已兑换</b>：Redis bitmap，1 bit/码（20 亿 ≈ 256MB）</li>
 *   <li><b>序号属于哪张券</b>：Redis ZSet 存"券id → 该券分到的最大序号"，
 *       查的时候取第一个 score >= 序号的券即可（每个券占一段连续区间）</li>
 * </ul>
 * <p>也就是说：<b>20 亿个码的额外存储是常数级的</b>，与码的数量无关。</p>
 */
public class CodeUtil {

    /** 异或密钥表（32 个），用于最后的数据混淆 */
    private static final long[] XOR_TABLE = {
            45139281907L, 61261925523L, 58169127203L, 27031786219L,
            64169927199L, 46169126943L, 32731286209L, 52082227349L,
            59169127063L, 36169126987L, 52082200939L, 61261925739L,
            32731286563L, 27031786427L, 56169127077L, 34111865001L,
            52082216763L, 61261925663L, 56169127113L, 45139282119L,
            32731286479L, 64169927233L, 41390251661L, 59169127121L,
            64169927321L, 55139282179L, 34111864881L, 46169127031L,
            58169127221L, 61261925523L, 36169126943L, 64169927363L,
    };

    /** 序列号加权运算的密钥表（16 组，按 fresh 选一组） */
    private static final int[][] PRIME_TABLE = {
            {23, 59, 241, 61, 607, 67, 977, 1217, 1289, 1601},
            {79, 83, 107, 439, 313, 619, 911, 1049, 1237},
            {173, 211, 499, 673, 823, 941, 1039, 1213, 1429, 1259},
            {31, 293, 311, 349, 431, 577, 757, 883, 1009, 1657},
            {353, 23, 367, 499, 599, 661, 719, 929, 1301, 1511},
            {103, 179, 353, 467, 577, 691, 811, 947, 1153, 1453},
            {213, 439, 257, 313, 571, 619, 743, 829, 983, 1103},
            {31, 151, 241, 349, 607, 677, 769, 823, 967, 1049},
            {61, 83, 109, 137, 151, 521, 701, 827, 1123},
            {23, 61, 199, 223, 479, 647, 739, 811, 947, 1019},
            {31, 109, 311, 467, 613, 743, 821, 881, 1031, 1171},
            {41, 173, 367, 401, 569, 683, 761, 883, 1009, 1181},
            {127, 283, 467, 577, 661, 773, 881, 967, 1097, 1289},
            {59, 137, 257, 347, 439, 547, 641, 839, 977, 1009},
            {61, 199, 313, 421, 613, 739, 827, 941, 1087, 1307},
            {19, 127, 241, 353, 499, 607, 811, 919, 1031, 1301}
    };

    private static final int FRESH_BIT_OFFSET = 32;
    private static final int CHECK_CODE_BIT_OFFSET = 36;
    private static final int FRESH_MASK = 0xF;
    private static final int CHECK_CODE_MASK = 0b11111111111111;
    private static final long PAYLOAD_MASK = 0xFFFFFFFFFL;
    private static final long SERIAL_NUM_MASK = 0xFFFFFFFFL;

    private CodeUtil() {
    }

    /**
     * 生成兑换码。
     *
     * @param serialNum 递增序列号（32 位，由 Redis 发号）
     * @param fresh     新鲜值，一般用券id（取低 4 位）
     * @return 10 位 Base32 兑换码
     */
    public static String generateCode(long serialNum, long fresh) {
        // 1. 新鲜值取低 4 位
        long f = fresh & FRESH_MASK;
        // 2. 拼接载荷：fresh(4) + serialNum(32) = 36 位
        long payload = (f << FRESH_BIT_OFFSET) | serialNum;
        // 3. 加权算校验码（14 位）
        long checkCode = calcCheckCode(payload, (int) f);
        // 4. 用校验码后 5 位选一个密钥，与载荷异或 —— 打乱递增规律
        payload ^= XOR_TABLE[(int) (checkCode & 0b11111)];
        // 5. 拼接明文：校验码(14) + 混淆后载荷(36) = 50 位
        long code = (checkCode << CHECK_CODE_BIT_OFFSET) | payload;
        // 6. Base32 → 10 个字符
        return Base32.encode(code);
    }

    /**
     * 解析兑换码，返回序列号。
     *
     * @throws BadRequestException 码格式错误、或校验码对不上（即伪造码）
     */
    public static long parseCode(String code) {
        long num;
        try {
            num = Base32.decode(code);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("无效兑换码");
        }
        // 1. 低 36 位是混淆后的载荷
        long payload = num & PAYLOAD_MASK;
        // 2. 高 14 位是校验码
        int checkCode = (int) (num >>> CHECK_CODE_BIT_OFFSET);
        // 3. 用同样的密钥异或，还原载荷
        payload ^= XOR_TABLE[(checkCode & 0b11111)];
        // 4. 取高 4 位得到 fresh
        int fresh = (int) ((payload >>> FRESH_BIT_OFFSET) & FRESH_MASK);
        // 5. ★ 用还原后的载荷重算校验码并比对 —— 对不上就是伪造码。
        //    这一步<b>不查任何存储</b>，成本极低，能挡掉绝大多数瞎编的码
        if (calcCheckCode(payload, fresh) != checkCode) {
            throw new BadRequestException("无效兑换码");
        }
        // 6. 低 32 位是序列号
        return payload & SERIAL_NUM_MASK;
    }

    /**
     * 计算校验码：载荷每 4 位一组，乘加权数后求和，取低 14 位。
     *
     * <p>权重表按 fresh 选一组（16 组）—— 这样不同券的码，
     * 即使序号相同，算出的校验码也不一样。</p>
     */
    private static long calcCheckCode(long payload, int fresh) {
        int[] table = PRIME_TABLE[fresh];
        long sum = 0;
        int index = 0;
        while (payload > 0 && index < table.length) {
            sum += (payload & 0xf) * table[index++];
            payload >>>= 4;
        }
        return sum & CHECK_CODE_MASK;
    }
}
