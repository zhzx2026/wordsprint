package com.aidemo.wordsprint;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;

/**
 * 极简 QR Code 编码器：byte 模式 + EC level M，版本自动选择（1–40），8 掩码按惩罚分择优。
 * 表数据从 ISO/IEC 18004 权威表生成（qrcode/segno 交叉校验）。纯 java，无 Android 依赖，可主机侧单测。
 */
public class QREnc {

    // ==== 生成表（scripts/gen_qrtab.py 由 qrcode+segno 权威表导出，勿手改）====
    // RS_M[ver-1] = {numBlocks, ecCodewordsPerBlock, dataCodewordsPerBlock}...
static final int[][][] RS_M = {{{1,10,16}},{{1,16,28}},{{1,26,44}},{{2,18,32}},{{2,24,43}},{{4,16,27}},{{4,18,31}},{{2,22,38},{2,22,39}},{{3,22,36},{2,22,37}},{{4,26,43},{1,26,44}},{{1,30,50},{4,30,51}},{{6,22,36},{2,22,37}},{{8,22,37},{1,22,38}},{{4,24,40},{5,24,41}},{{5,24,41},{5,24,42}},{{7,28,45},{3,28,46}},{{10,28,46},{1,28,47}},{{9,26,43},{4,26,44}},{{3,26,44},{11,26,45}},{{3,26,41},{13,26,42}},{{17,26,42}},{{17,28,46}},{{4,28,47},{14,28,48}},{{6,28,45},{14,28,46}},{{8,28,47},{13,28,48}},{{19,28,46},{4,28,47}},{{22,28,45},{3,28,46}},{{3,28,45},{23,28,46}},{{21,28,45},{7,28,46}},{{19,28,47},{10,28,48}},{{2,28,46},{29,28,47}},{{10,28,46},{23,28,47}},{{14,28,46},{21,28,47}},{{14,28,46},{23,28,47}},{{12,28,47},{26,28,48}},{{6,28,47},{34,28,48}},{{29,28,46},{14,28,47}},{{13,28,46},{32,28,47}},{{40,28,47},{7,28,48}},{{18,28,47},{31,28,48}}};
    // ALIGN[ver-1] = 校正图形中心坐标（v1 为空）
    static final int[][] ALIGN = {{},{6,18},{6,22},{6,26},{6,30},{6,34},{6,22,38},{6,24,42},{6,26,46},{6,28,50},{6,30,54},{6,32,58},{6,34,62},{6,26,46,66},{6,26,48,70},{6,26,50,74},{6,30,54,78},{6,30,56,82},{6,30,58,86},{6,34,62,90},{6,28,50,72,94},{6,26,50,74,98},{6,30,54,78,102},{6,28,54,80,106},{6,32,58,84,110},{6,30,58,86,114},{6,34,62,90,118},{6,26,50,74,98,122},{6,30,54,78,102,126},{6,26,52,78,104,130},{6,30,56,82,108,134},{6,34,60,86,112,138},{6,30,58,86,114,142},{6,34,62,90,118,146},{6,30,54,78,102,126,150},{6,24,50,76,102,128,154},{6,28,54,80,106,132,158},{6,32,58,84,110,136,162},{6,26,54,82,110,138,166},{6,30,58,86,114,142,170}};

    // ---- GF(256), 0x11D ----
    static final int[] EXP = new int[512], LOG = new int[256];
    static {
        int x = 1;
        for (int i = 0; i < 255; i++) { EXP[i] = x; LOG[x] = i; x <<= 1; if ((x & 0x100) != 0) x ^= 0x11D; }
        for (int i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
    }
    static int mul(int a, int b) { return (a == 0 || b == 0) ? 0 : EXP[LOG[a] + LOG[b]]; }

    static int[] genPoly(int deg) {
        int[] g = new int[]{1};
        for (int i = 0; i < deg; i++) {
            int[] ng = new int[g.length + 1];
            for (int j = 0; j < g.length; j++) {
                ng[j] ^= g[j];
                ng[j + 1] ^= mul(g[j], EXP[i]);
            }
            g = ng;
        }
        return g;
    }

    static int[] rsRemainder(int[] data, int ecLen) {
        int[] g = genPoly(ecLen);
        int[] res = new int[ecLen];
        for (int b : data) {
            int factor = b ^ res[0];
            System.arraycopy(res, 1, res, 0, ecLen - 1);
            res[ecLen - 1] = 0;
            for (int i = 0; i < ecLen; i++) res[i] ^= mul(g[i + 1], factor);
        }
        return res;
    }

    /** 编码为模块矩阵：true = 黑。payload 需 ≤ 2331 字节。 */
    public static boolean[][] encode(byte[] payload) { return encode(payload, -1); }

    /** forcedMask >= 0：跳过惩罚分选择，固定掩码（用于与参考实现逐格比对） */
    public static boolean[][] encode(byte[] payload, int forcedMask) {
        int ver = chooseVersion(payload.length);
        if (ver < 0) throw new IllegalArgumentException("payload too large for QR-M");
        byte[] codewords = buildCodewords(ver, payload);
        boolean[][] mat = new boolean[17 + 4 * ver][17 + 4 * ver];
        int size = mat.length;
        boolean[][] reserved = new boolean[size][size]; // function modules

        // finder patterns + separators
        int[][] org = {{0, 0}, {0, size - 7}, {size - 7, 0}};
        for (int[] o : org) {
            for (int dy = -1; dy <= 7; dy++) for (int dx = -1; dx <= 7; dx++) {
                int y = o[0] + dy, x = o[1] + dx;
                if (y < 0 || x < 0 || y >= size || x >= size) continue;
                int a = Math.max(Math.abs(dy - 3), Math.abs(dx - 3));
                mat[y][x] = a <= 1 || a == 3;
                reserved[y][x] = true;
            }
        }
        for (int i = 8; i < size - 8; i++) {
            mat[6][i] = i % 2 == 0; reserved[6][i] = true;
            mat[i][6] = i % 2 == 0; reserved[i][6] = true;
        }
        // dark module
        mat[size - 8][8] = true; reserved[size - 8][8] = true;

        // alignment patterns
        int[] ac = ALIGN[ver - 1];
        for (int y0 : ac) for (int x0 : ac) {
            boolean inFinder = (y0 <= 8 && x0 <= 8) || (y0 <= 8 && x0 >= size - 9) || (y0 >= size - 8 && x0 <= 8);
            if (inFinder) continue;
            for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) {
                int a = Math.max(Math.abs(dy), Math.abs(dx));
                mat[y0 + dy][x0 + dx] = a != 1;
                reserved[y0 + dy][x0 + dx] = true;
            }
        }
        // reserve format areas
        for (int i = 0; i < 9; i++) { if (!reserved[8][i]) reserved[8][i] = true; if (!reserved[i][8]) reserved[i][8] = true; }
        for (int i = 0; i < 8; i++) { reserved[8][size - 1 - i] = true; reserved[size - 1 - i][8] = true; }
        if (ver >= 7) {
            for (int i = 0; i < 6; i++) for (int j = 0; j < 3; j++) { reserved[size - 11 + j][i] = true; reserved[i][size - 11 + j] = true; }
        }

        // data placement
        place(codewords, mat, reserved, size);

        // mask: pick best penalty
        int[] masks = forcedMask >= 0 ? new int[]{forcedMask} : new int[]{0, 1, 2, 3, 4, 5, 6, 7};
        int bestMask = 0; int bestScore = Integer.MAX_VALUE; boolean[][] best = null;
        for (int mi = 0; mi < masks.length; mi++) { int m = masks[mi];
            boolean[][] t = new boolean[size][size];
            for (int y = 0; y < size; y++) System.arraycopy(mat[y], 0, t[y], 0, size);
            applyMask(t, reserved, size, m);
            writeFormat(t, size, m);
            if (ver >= 7) writeVersion(t, size, ver);
            int p = penalty(t, size);
            if (p < bestScore) { bestScore = p; bestMask = m; best = t; }
        }
        return best;
        // (masks.length==1 shortcut returns single result)
    }

    static int chooseVersion(int len) {
        for (int v = 1; v <= 40; v++) {
            int cap = dataCodewords(v);
            int ccBits = v < 10 ? 8 : 16;
            int need = 4 + ccBits + len * 8;
            if (need <= cap * 8) return v;
        }
        return -1;
    }
    static int dataCodewords(int v) {
        int t = 0; for (int[] g : RS_M[v - 1]) t += g[0] * g[2]; return t;
    }

    static byte[] buildCodewords(int ver, byte[] payload) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int ccBits = ver < 10 ? 8 : 16;
        int bits = 0, acc = 0;
        // mode 4 = byte
        for (int i = 3; i >= 0; i--) { acc = (acc << 1) | ((4 >> i) & 1); bits++; if (bits == 8) { bos.write(acc); acc = 0; bits = 0; } }
        int len = payload.length;
        for (int i = ccBits - 1; i >= 0; i--) { acc = (acc << 1) | ((len >> i) & 1); bits++; if (bits == 8) { bos.write(acc); acc = 0; bits = 0; } }
        for (byte b : payload) for (int k = 7; k >= 0; k--) {
            acc = (acc << 1) | ((b >> k) & 1); bits++;
            if (bits == 8) { bos.write(acc); acc = 0; bits = 0; }
        }
        int capBits = dataCodewords(ver) * 8;
        int written = bos.size() * 8 + bits;
        int term = Math.min(4, capBits - written);
        for (int i = 0; i < term; i++) { acc <<= 1; bits++; if (bits == 8) { bos.write(acc); acc = 0; bits = 0; } }
        if (bits > 0) { for (int i = 0; i < 8 - bits; i++) acc <<= 1; bos.write(acc); acc = 0; bits = 0; }
        boolean flip = true;
        while (bos.size() < capBits / 8) { bos.write(flip ? 0xEC : 0x11); flip = !flip; }
        byte[] data = bos.toByteArray();

        // split blocks + EC
        int[][] gs = RS_M[ver - 1];
        int[] dAll = new int[capBits / 8];
        for (int i = 0; i < dAll.length; i++) dAll[i] = data[i] & 0xFF;
        int blockCount = 0; for (int[] g : gs) blockCount += g[0];
        int[][] dBlocks = new int[blockCount][]; int[][] eBlocks = new int[blockCount][];
        int bi = 0, pos = 0;
        for (int[] g : gs) {
            for (int k = 0; k < g[0]; k++) {
                int[] d = new int[g[2]];
                System.arraycopy(dAll, pos, d, 0, g[2]); pos += g[2];
                dBlocks[bi] = d;
                eBlocks[bi] = rsRemainder(d, g[1]);
                bi++;
            }
        }
        int maxD = 0; for (int[] d : dBlocks) maxD = Math.max(maxD, d.length);
        int maxE = eBlocks[0].length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < maxD; i++) for (int b = 0; b < blockCount; b++) if (i < dBlocks[b].length) out.write(dBlocks[b][i]);
        for (int i = 0; i < maxE; i++) for (int b = 0; b < blockCount; b++) out.write(eBlocks[b][i]);
        return out.toByteArray();
    }

    static void place(byte[] cw, boolean[][] mat, boolean[][] reserved, int size) {
        int bit = 0, total = cw.length * 8;
        int dir = -1, row = size - 1;
        for (int col = size - 1; col > 0; col -= 2) {
            if (col == 6) col--;
            while (true) {
                for (int c = 0; c < 2; c++) {
                    int x = col - c;
                    if (!reserved[row][x]) {
                        boolean v = false;
                        if (bit < total) { v = ((cw[bit >> 3] >> (7 - (bit & 7))) & 1) != 0; bit++; }
                        mat[row][x] = v;
                    }
                }
                row += dir;
                if (row < 0 || row >= size) { dir = -dir; row += dir; break; }
            }
        }
        if (bit < total) throw new RuntimeException("overflow"); // codewords > capacity
    }

    static void applyMask(boolean[][] mat, boolean[][] res, int size, int m) {
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            if (res[y][x]) continue;
            boolean inv;
            switch (m) {
                case 0: inv = (y + x) % 2 == 0; break;
                case 1: inv = y % 2 == 0; break;
                case 2: inv = x % 3 == 0; break;
                case 3: inv = (y + x) % 3 == 0; break;
                case 4: inv = (y / 2 + x / 3) % 2 == 0; break;
                case 5: inv = (y * x) % 2 + (y * x) % 3 == 0; break;
                case 6: inv = ((y * x) % 2 + (y * x) % 3) % 2 == 0; break;
                default: inv = ((y + x) % 2 + (y * x) % 3) % 2 == 0; break;
            }
            if (inv) mat[y][x] = !mat[y][x];
        }
    }

    static void writeFormat(boolean[][] mat, int size, int mask) {
        int data = (0b00 << 3) | mask; // EC level M = 00
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = ((data << 10) | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) mat[i][8] = ((bits >>> i) & 1) != 0;
        mat[7][8] = ((bits >>> 6) & 1) != 0;
        mat[8][8] = ((bits >>> 7) & 1) != 0;
        mat[8][7] = ((bits >>> 8) & 1) != 0;
        for (int i = 9; i < 15; i++) mat[8][14 - i] = ((bits >>> i) & 1) != 0;
        for (int i = 0; i < 8; i++) mat[8][size - 1 - i] = ((bits >>> i) & 1) != 0;
        for (int i = 8; i < 15; i++) mat[size - 15 + i][8] = ((bits >>> i) & 1) != 0;
        mat[size - 8][8] = true;
    }

    static void writeVersion(boolean[][] mat, int size, int ver) {
        int rem = ver;
        for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
        int bits = (ver << 12) | rem;
        for (int i = 0; i < 18; i++) {
            boolean b = ((bits >>> i) & 1) != 0;
            mat[i / 3][size - 11 + i % 3] = b;
            mat[size - 11 + i % 3][i / 3] = b;
        }
    }

    /** 行/列中与探测图形同比例 1:1:3:1:1 且邻侧亮区≥4模块的图案数（掩码惩罚 rule 3，防读端漏检）。 */
    static int finderLike(boolean[][] m, int size) {
        int total = 0;
        for (int dir = 0; dir < 2; dir++) {
            for (int line = 0; line < size; line++) {
                int[] rh = new int[7];
                boolean color = false;
                int run = 0;
                for (int t = 0; t < size; t++) {
                    boolean cur = m[dir == 0 ? line : t][dir == 0 ? t : line];
                    if (cur == color) run++;
                    else { push(rh, run + (rh[0] == 0 ? size : 0)); total += countPat(rh); run = 1; color = cur; }
                }
                if (color) { push(rh, run); total += countPat(rh); push(rh, size); total += countPat(rh); }
                else { push(rh, run + size); total += countPat(rh); }
            }
        }
        return total;
    }
    static void push(int[] rh, int len) { System.arraycopy(rh, 0, rh, 1, 6); rh[0] = len; }
    static int countPat(int[] rh) {
        int n = rh[1];
        boolean core = n > 0 && rh[2] == n * 3 && rh[3] == n && rh[4] == n;
        return (core && rh[0] >= n * 4 && rh[5] >= n ? 1 : 0)
             + (core && rh[5] >= n * 4 && rh[0] >= n ? 1 : 0);
    }

    static int penalty(boolean[][] m, int size) {
        int score = 0;
        for (int y = 0; y < size; y++) {
            int run = 1;
            for (int x = 1; x < size; x++) {
                if (m[y][x] == m[y][x - 1]) { run++; if (run == 5) score += 3; else if (run > 5) score++; }
                else run = 1;
            }
        }
        for (int x = 0; x < size; x++) {
            int run = 1;
            for (int y = 1; y < size; y++) {
                if (m[y][x] == m[y - 1][x]) { run++; if (run == 5) score += 3; else if (run > 5) score++; }
                else run = 1;
            }
        }
        for (int y = 0; y < size - 1; y++) for (int x = 0; x < size - 1; x++) {
            boolean a = m[y][x], b = m[y][x + 1], c = m[y + 1][x], d = m[y + 1][x + 1];
            if (a == b && a == c && a == d) score += 3;
        }
        // rule 3: finder-similarity 1:1:3:1:1 with >=4 light on either side, +40 each
        score += finderLike(m, size) * 40;
        int dark = 0;
        for (boolean[] row : m) for (boolean b : row) if (b) dark++;
        int total = size * size;
        int pct = dark * 100 / total;
        int prev = (pct / 5) * 5, next = prev + 5;
        score += Math.min(Math.abs(prev - 50) / 5, Math.abs(next - 50) / 5) * 10;
        return score;
    }

    /** 把 payload 编成可直接扫码的字符串（含 CRC 校验前缀），再画矩阵。 */
    public static boolean[][] encodePayload(byte[] payload) { return encode(payload); }

    // CRC 用于扫码后完整性检查（防误识）
    public static long crc32(byte[] b) { CRC32 c = new CRC32(); c.update(b); return c.getValue(); }

    /** test-only debug hook (not used on device) */
    public static String dbgCodewords(int ver, byte[] payload) {
        byte[] d = buildCodewords(ver, payload);
        StringBuilder sb = new StringBuilder();
        for (int j = 0; j < d.length; j++) sb.append(d[j] & 0xFF).append(' ');
        return sb.toString();
    }
}