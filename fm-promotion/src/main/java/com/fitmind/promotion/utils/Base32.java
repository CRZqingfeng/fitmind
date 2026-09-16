package com.fitmind.promotion.utils;

/**
 * Base32 编解码（自定义字母表）。
 *
 * <p><b>为什么用 Base32 而不是 Base64</b>：兑换码是要<b>给人手输</b>的。</p>
 * <ul>
 *   <li>Base64 含 {@code + / =} 等符号，还有大小写 —— 抄写和口述都容易错</li>
 *   <li>这里去掉了易混淆字符（0/O、1/I/L），全部大写数字，
 *       50 位二进制正好编码成 10 个字符（50 / 5 = 10，无填充位）</li>
 * </ul>
 *
 * <p>50 位 ÷ 5 位/字符 = 10 字符，整除，不需要 padding ——
 * 这也是为什么明文设计成 50 位而不是别的长度。</p>
 */
public class Base32 {

    /**
     * 字母表：Crockford Base32 风格 —— 纯数字与大写字母，共 32 个。
     *
     * <p>去掉了易混淆的 {@code I / L / O / U}（与 1、1、0、V 易看错），
     * 保留 {@code 0-9} 与其余大写字母。</p>
     *
     * <p>⚠️ <b>字母表里绝不能出现任何 URL 保留字符</b>（{@code ? & # / + =} 等）。
     * 兑换码是通过路径参数传的（{@code POST /user-coupons/exchange/{code}}），
     * 一旦码里含 {@code ?}，整个 URL 会被从问号处截断，
     * 后端拿到半个码就报"无效兑换码" —— 本项目就踩过这个坑
     * （最初的字母表第 32 个字符写成了 {@code '?'}，1/32 的码会失效）。</p>
     */
    private static final char[] ALPHABET = {
            '0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'A', 'B', 'C', 'D', 'E', 'F',
            'G', 'H', 'J', 'K', 'M', 'N', 'P', 'Q',
            'R', 'S', 'T', 'V', 'W', 'X', 'Y', 'Z'
    };

    private static final int MASK = 0x1F;   // 低 5 位
    private static final int BITS = 5;
    private static final int CODE_LENGTH = 10;

    private Base32() {
    }

    /** 将 50 位以内的 long 编码成 10 位 Base32 字符串 */
    public static String encode(long num) {
        char[] chars = new char[CODE_LENGTH];
        for (int i = CODE_LENGTH - 1; i >= 0; i--) {
            chars[i] = ALPHABET[(int) (num & MASK)];
            num >>>= BITS;
        }
        return new String(chars);
    }

    /**
     * 解码。
     *
     * @throws IllegalArgumentException 字符不在字母表内（即码本身非法）
     */
    public static long decode(String code) {
        if (code == null || code.length() != CODE_LENGTH) {
            throw new IllegalArgumentException("兑换码长度不正确");
        }
        long num = 0;
        for (int i = 0; i < code.length(); i++) {
            int v = indexOf(code.charAt(i));
            if (v < 0) {
                throw new IllegalArgumentException("兑换码包含非法字符：" + code.charAt(i));
            }
            num = (num << BITS) | v;
        }
        return num;
    }

    private static int indexOf(char c) {
        for (int i = 0; i < ALPHABET.length; i++) {
            if (ALPHABET[i] == c) {
                return i;
            }
        }
        return -1;
    }
}
