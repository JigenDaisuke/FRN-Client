package net.voipitalia.frnlite.gsm;

/**
 * GSM 06.10 full-rate codec in "WAV49" (Microsoft GSM) framing, as used by FRN.
 *
 * A direct, bit-exact port of the project's Python codec (frnclient/gsm610.py),
 * which is itself bit-exact against libgsm with GSM_OPT_WAV49.  Pure Java, no
 * Android dependencies, so it is verified on the JVM against the Python output.
 *
 * FRN voice packets are 325 bytes = 5 blocks of 65 bytes; each 65-byte block
 * holds two GSM frames (2 x 160 samples, 8 kHz mono, 16-bit), packed LSB-first.
 */
public final class Gsm {

    public static final int FRAME_SAMPLES = 160;
    public static final int BLOCK_SAMPLES = 320;
    public static final int BLOCK_BYTES = 65;
    public static final int PACKET_BLOCKS = 5;
    public static final int PACKET_SAMPLES = BLOCK_SAMPLES * PACKET_BLOCKS; // 1600
    public static final int PACKET_BYTES = BLOCK_BYTES * PACKET_BLOCKS;     // 325

    private static final int MIN_WORD = -32768;
    private static final int MAX_WORD = 32767;

    // ------------------------------------------------------------- fixed point
    private static int w16(int x) { return (short) x; }

    private static int sat(int x) { return x < MIN_WORD ? MIN_WORD : (x > MAX_WORD ? MAX_WORD : x); }

    private static int add(int a, int b) { return sat(a + b); }

    private static int sub(int a, int b) { return sat(a - b); }

    private static int mult(int a, int b) {
        if (a == MIN_WORD && b == MIN_WORD) return MAX_WORD;
        return (a * b) >> 15;
    }

    private static int multR(int a, int b) {
        if (a == MIN_WORD && b == MIN_WORD) return MAX_WORD;
        return w16((a * b + 16384) >> 15);
    }

    private static int abs16(int a) {
        if (a < 0) return a == MIN_WORD ? MAX_WORD : -a;
        return a;
    }

    private static int lAdd(int a, int b) {
        long s = (long) a + (long) b;
        if (s < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        if (s > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) s;
    }

    private static int norm(int a) {
        if (a < 0) {
            if (a <= -1073741824) return 0;
            a = ~a;
        }
        if (a == 0) return 31;
        return Integer.numberOfLeadingZeros(a) - 1;
    }

    private static int asl(int a, int n) {
        if (n >= 16) return 0;
        if (n <= -16) return a < 0 ? -1 : 0;
        if (n < 0) return asr(a, -n);
        return w16(a << n);
    }

    private static int asr(int a, int n) {
        if (n >= 16) return a < 0 ? -1 : 0;
        if (n <= -16) return 0;
        if (n < 0) return w16(a << -n);
        return a >> n;
    }

    private static int div(int num, int denum) {
        if (num == 0) return 0;
        long lNum = num;
        int divv = 0;
        for (int i = 0; i < 15; i++) {
            divv <<= 1;
            lNum <<= 1;
            if (lNum >= denum) {
                lNum -= denum;
                divv += 1;
            }
        }
        return divv;
    }

    // --------------------------------------------------------------- tables
    private static final int[] DLB = {6554, 16384, 26214, 32767};
    private static final int[] QLB = {3277, 11469, 21299, 32767};
    private static final int[] NRFAC = {29128, 26215, 23832, 21846, 20165, 18725, 17476, 16384};
    private static final int[] FAC = {18431, 20479, 22527, 24575, 26623, 28671, 30719, 32767};
    // _LAR_D rows: {b2, mic, inva}
    private static final int[][] LAR_D = {
            {0, -32, 13107}, {0, -32, 13107}, {4096, -16, 13107}, {-5120, -16, 13107},
            {188, -8, 19223}, {-3584, -8, 17476}, {-682, -4, 31454}, {-2288, -4, 29708}};
    // _LAR_Q rows: {A, B, MAC, MIC}
    private static final int[][] LAR_Q = {
            {20480, 0, 31, -32}, {20480, 0, 31, -32}, {20480, 2048, 15, -16},
            {20480, -2560, 15, -16}, {13964, 94, 7, -8}, {15360, -1792, 7, -8},
            {8534, -341, 3, -4}, {9036, -1144, 3, -4}};
    private static final int[] H = {-134, -374, 0, 2054, 5741, 8192, 5741, 2054, 0, -374, -134};
    private static final int[] LAR_BITS = {6, 6, 5, 5, 4, 4, 3, 3};
    private static final int[] FRAME_LAYOUT; // 8 LAR + 4*(N,b,M,xmax + 13 xmc)

    static {
        int[] fl = new int[8 + 4 * 17];
        int p = 0;
        for (int b : LAR_BITS) fl[p++] = b;
        for (int k = 0; k < 4; k++) {
            fl[p++] = 7; fl[p++] = 2; fl[p++] = 2; fl[p++] = 6;
            for (int i = 0; i < 13; i++) fl[p++] = 3;
        }
        FRAME_LAYOUT = fl;
    }

    // ---------------------------------------------- LAR decode / interpolation
    private static int[] decodeLar(int[] LARc) {
        int[] out = new int[8];
        for (int i = 0; i < 8; i++) {
            int b2 = LAR_D[i][0], mic = LAR_D[i][1], inva = LAR_D[i][2];
            int t = w16(add(LARc[i], mic) << 10);
            t = sub(t, b2);
            t = multR(inva, t);
            out[i] = add(t, t);
        }
        return out;
    }

    private static int larToRpOne(int v) {
        int t;
        if (v < 0) {
            t = (v == MIN_WORD) ? MAX_WORD : -v;
            t = t < 11059 ? (t << 1) : (t < 20070 ? (t + 11059) : add(t >> 2, 26112));
            return -t;
        } else {
            t = v;
            t = t < 11059 ? (t << 1) : (t < 20070 ? (t + 11059) : add(t >> 2, 26112));
            return t;
        }
    }

    private static int[] larToRp(int[] LARp) {
        int[] rp = new int[8];
        for (int i = 0; i < 8; i++) rp[i] = larToRpOne(LARp[i]);
        return rp;
    }

    /** Four interpolation segments: rp[seg][8], and START/LENGTH below. */
    static final int[] SEG_START = {0, 13, 27, 40};
    static final int[] SEG_LEN = {13, 14, 13, 120};

    private static int[][] interpSets(int[] prev, int[] cur) {
        int[] a = new int[8], b = new int[8], c = new int[8];
        for (int i = 0; i < 8; i++) {
            a[i] = add(add(prev[i] >> 2, cur[i] >> 2), prev[i] >> 1);
            b[i] = add(prev[i] >> 1, cur[i] >> 1);
            c[i] = add(add(prev[i] >> 2, cur[i] >> 2), cur[i] >> 1);
        }
        return new int[][]{larToRp(a), larToRp(b), larToRp(c), larToRp(cur.clone())};
    }

    // ---------------------------------------------------------- bit packing
    private static void putFields(byte[] block, int[] bitPos, int[] p0Nc, int[][] frame) {
        // frame = {LARc[8], Nc[4], bc[4], Mc[4], xmaxc[4], xmc[52]}
    }

    private static int[] frameFieldVals(int[][] f) {
        // returns flat list of values matching FRAME_LAYOUT order
        int[] LARc = f[0], Nc = f[1], bc = f[2], Mc = f[3], xmaxc = f[4], xmc = f[5];
        int[] vals = new int[FRAME_LAYOUT.length];
        int p = 0;
        for (int i = 0; i < 8; i++) vals[p++] = LARc[i];
        for (int k = 0; k < 4; k++) {
            vals[p++] = Nc[k]; vals[p++] = bc[k]; vals[p++] = Mc[k]; vals[p++] = xmaxc[k];
            for (int i = 0; i < 13; i++) vals[p++] = xmc[k * 13 + i];
        }
        return vals;
    }

    public static byte[] packBlock(int[][] p1, int[][] p2) {
        byte[] out = new byte[BLOCK_BYTES];
        int[] v1 = frameFieldVals(p1), v2 = frameFieldVals(p2);
        int bit = 0;
        bit = packInto(out, bit, v1);
        bit = packInto(out, bit, v2);
        return out;
    }

    private static int packInto(byte[] out, int bit, int[] vals) {
        for (int i = 0; i < FRAME_LAYOUT.length; i++) {
            int n = FRAME_LAYOUT[i];
            int v = vals[i] & ((1 << n) - 1);
            for (int b = 0; b < n; b++) {
                if (((v >> b) & 1) != 0) out[bit >> 3] |= (1 << (bit & 7));
                bit++;
            }
        }
        return bit;
    }

    public static int[][][] unpackBlock(byte[] data) {
        int[][][] frames = new int[2][][];
        int bit = 0;
        for (int fr = 0; fr < 2; fr++) {
            int[] vals = new int[FRAME_LAYOUT.length];
            for (int i = 0; i < FRAME_LAYOUT.length; i++) {
                int n = FRAME_LAYOUT[i];
                int v = 0;
                for (int b = 0; b < n; b++) {
                    if ((data[bit >> 3] & (1 << (bit & 7))) != 0) v |= (1 << b);
                    bit++;
                }
                vals[i] = v;
            }
            int[] LARc = new int[8], Nc = new int[4], bc = new int[4], Mc = new int[4],
                    xmaxc = new int[4], xmc = new int[52];
            int p = 0;
            for (int i = 0; i < 8; i++) LARc[i] = vals[p++];
            for (int k = 0; k < 4; k++) {
                Nc[k] = vals[p++]; bc[k] = vals[p++]; Mc[k] = vals[p++]; xmaxc[k] = vals[p++];
                for (int i = 0; i < 13; i++) xmc[k * 13 + i] = vals[p++];
            }
            frames[fr] = new int[][]{LARc, Nc, bc, Mc, xmaxc, xmc};
        }
        return frames;
    }

    // ---------------------------------------- APCM helpers (shared enc/dec)
    private static int[] xmaxcToExpMant(int xmaxc) {
        int exp = 0;
        if (xmaxc > 15) exp = (xmaxc >> 3) - 1;
        int mant = xmaxc - (exp << 3);
        if (mant == 0) return new int[]{-4, 7};
        while (mant <= 7) {
            mant = (mant << 1) | 1;
            exp -= 1;
        }
        return new int[]{exp, mant - 8};
    }

    private static int[] apcmInverse(int[] xMc, int mant, int exp) {
        int temp1 = FAC[mant];
        int temp2 = sub(6, exp);
        int temp3 = asl(1, sub(temp2, 1));
        int[] out = new int[xMc.length];
        for (int i = 0; i < xMc.length; i++) {
            int t = w16(((xMc[i] << 1) - 7) << 12);
            t = multR(temp1, t);
            t = add(t, temp3);
            out[i] = asr(t, temp2);
        }
        return out;
    }

    private static int[] gridPosition(int Mc, int[] xMp) {
        int[] ep = new int[40];
        for (int i = 0; i < 13; i++) ep[Mc + 3 * i] = xMp[i];
        return ep;
    }

    // ------------------------------------------------------------- Encoder
    public static final class Encoder {
        private int[] dp0 = new int[280];
        private int z1 = 0;
        private int L_z2 = 0;
        private int mp = 0;
        private int[] u = new int[8];
        private int[][] LARpp = {new int[8], new int[8]};
        private int j = 0;

        private int[] preprocess(int[] s) {
            int z1 = this.z1, L_z2 = this.L_z2, mp = this.mp;
            int[] so = new int[160];
            for (int k = 0; k < 160; k++) {
                int SO = (s[k] >> 1) & ~3;
                int s1 = SO - z1;
                z1 = SO;
                long L_s2 = ((long) s1) << 15;   // s1 fits in 17 bits, shift <<15 fits long
                int msp = L_z2 >> 15;
                int lsp = L_z2 - (msp << 15);
                L_s2 += (lsp * 32735 + 16384) >> 15;
                L_z2 = lAdd((int) (msp * 32735L), (int) L_s2);
                int L_temp = lAdd(L_z2, 16384);
                msp = multR(mp, -28180);
                mp = w16(L_temp >> 15);
                so[k] = add(mp, msp);
            }
            this.z1 = z1; this.L_z2 = L_z2; this.mp = mp;
            return so;
        }

        private int[][] lpc(int[] sIn) {
            int[] s = sIn;
            int smax = 0;
            for (int v : s) { int a = abs16(v); if (a > smax) smax = a; }
            int scalauto = smax == 0 ? 0 : 4 - norm(smax << 16);
            if (scalauto > 0) {
                int f = 16384 >> (scalauto - 1);
                int[] s2 = new int[160];
                for (int i = 0; i < 160; i++) s2[i] = multR(s[i], f);
                s = s2;
            }
            int[] L_ACF = new int[9];
            for (int k = 0; k < 9; k++) {
                long acc = 0;
                for (int i = 0; i < 160 - k; i++) acc += (long) s[i + k] * (long) s[i];
                L_ACF[k] = (int) (acc << 1);
            }
            if (scalauto > 0) {
                int[] s2 = new int[160];
                for (int i = 0; i < 160; i++) s2[i] = w16(s[i] << scalauto);
                s = s2;
            }
            int[] r = new int[8];
            if (L_ACF[0] != 0) {
                int temp = norm(L_ACF[0]);
                int[] ACF = new int[9];
                for (int i = 0; i < 9; i++) ACF[i] = w16((L_ACF[i] << temp) >> 16);
                int[] K = new int[9];
                K[0] = 0;
                for (int i = 1; i < 8; i++) K[i] = ACF[i];
                K[8] = 0;
                int[] P = new int[9];
                System.arraycopy(ACF, 0, P, 0, 9);
                for (int n = 1; n <= 8; n++) {
                    int t = abs16(P[1]);
                    if (P[0] < t) break;
                    int rv = div(t, P[0]);
                    if (P[1] > 0) rv = -rv;
                    r[n - 1] = rv;
                    if (n == 8) break;
                    P[0] = add(P[0], multR(P[1], rv));
                    for (int m = 1; m < 9 - n; m++) {
                        P[m] = add(P[m + 1], multR(K[m], rv));
                        K[m] = add(K[m], multR(P[m + 1], rv));
                    }
                }
            }
            int[] LARc = new int[8];
            for (int i = 0; i < 8; i++) {
                int v = r[i];
                int t = abs16(v);
                if (t < 22118) t >>= 1;
                else if (t < 31130) t -= 11059;
                else t = (t - 26112) << 2;
                int lar = v < 0 ? -t : t;
                int A = LAR_Q[i][0], B = LAR_Q[i][1], MAC = LAR_Q[i][2], MIC = LAR_Q[i][3];
                t = mult(A, lar);
                t = add(t, B);
                t = add(t, 256);
                t >>= 9;
                LARc[i] = t > MAC ? (MAC - MIC) : (t < MIC ? 0 : t - MIC);
            }
            return new int[][]{s, LARc};
        }

        private void shortTermAnalysis(int[] LARc, int[] s) {
            int[] cur = decodeLar(LARc);
            LARpp[j] = cur;
            j ^= 1;
            int[] prev = LARpp[j];
            int[][] rps = interpSets(prev, cur);
            for (int seg = 0; seg < 4; seg++) {
                int[] rp = rps[seg];
                int start = SEG_START[seg], n = SEG_LEN[seg];
                for (int i = start; i < start + n; i++) {
                    int di = s[i], sav = s[i];
                    for (int k = 0; k < 8; k++) {
                        int ui = u[k];
                        int rpi = rp[k];
                        u[k] = sav;
                        sav = ui + ((rpi * di + 16384) >> 15);
                        di = di + ((rpi * ui + 16384) >> 15);
                        if (sav > MAX_WORD) sav = MAX_WORD; else if (sav < MIN_WORD) sav = MIN_WORD;
                        if (di > MAX_WORD) di = MAX_WORD; else if (di < MIN_WORD) di = MIN_WORD;
                    }
                    s[i] = di;
                }
            }
        }

        private int[] ltpParams(int[] d, int[] dp, int base) {
            int dmax = 0;
            for (int v : d) { int a = abs16(v); if (a > dmax) dmax = a; }
            int temp = dmax == 0 ? 0 : norm(dmax << 16);
            int scal = temp > 6 ? 0 : 6 - temp;
            int[] wt = new int[40];
            for (int i = 0; i < 40; i++) wt[i] = d[i] >> scal;
            long Lmax = 0;
            int Nc = 40;
            long bestL = 0;
            boolean found = false;
            for (int lam = 40; lam <= 120; lam++) {
                long L = 0;
                for (int k = 0; k < 40; k++) L += (long) wt[k] * (long) dp[base + k - lam];
                if (L > bestL) { bestL = L; Nc = lam; found = true; }
            }
            if (found && bestL > 0) Lmax = bestL; else { Nc = 40; Lmax = 0; }
            Lmax <<= 1;
            Lmax >>= (6 - scal);
            long Lpower = 0;
            for (int k = 0; k < 40; k++) {
                int t = dp[base + k - Nc] >> 3;
                Lpower += (long) t * (long) t;
            }
            Lpower <<= 1;
            if (Lmax <= 0) return new int[]{0, Nc};
            if (Lmax >= Lpower) return new int[]{3, Nc};
            int tnorm = norm((int) Lpower);
            int R = w16((int) ((Lmax << tnorm) >> 16));
            int S = w16((int) ((Lpower << tnorm) >> 16));
            int bc = 0;
            while (bc <= 2) {
                if (R <= mult(S, DLB[bc])) break;
                bc++;
            }
            return new int[]{bc, Nc};
        }

        private int[][] rpeEncode(int[] e) {
            int[] x = new int[40];
            for (int k = 0; k < 40; k++) {
                long L = 4096;
                for (int i = 0; i < 11; i++) L += (long) e[k + i] * (long) H[i];
                L >>= 13;
                x[k] = (int) (L < MIN_WORD ? MIN_WORD : (L > MAX_WORD ? MAX_WORD : L));
            }
            long common = 0;
            for (int i = 1; i < 13; i++) { int t = x[3 * i] >> 2; common += (long) t * t; }
            long e0 = 0; { int t = x[0] >> 2; e0 = (long) t * t; }
            long EM = (common + e0) << 1;
            int Mc = 0;
            for (int m = 1; m <= 2; m++) {
                long en = 0;
                for (int i = 0; i < 13; i++) { int t = x[m + 3 * i] >> 2; en += (long) t * t; }
                long L = en << 1;
                if (L > EM) { Mc = m; EM = L; }
            }
            { int t = x[3 + 3 * 12] >> 2; long L = (common + (long) t * t) << 1; if (L > EM) Mc = 3; }
            int[] xM = new int[13];
            for (int i = 0; i < 13; i++) xM[i] = x[Mc + 3 * i];
            int xmax = 0;
            for (int v : xM) { int a = abs16(v); if (a > xmax) xmax = a; }
            int exp = 0, temp = xmax >> 9, itest = 0;
            for (int i = 0; i < 6; i++) {
                itest |= (temp <= 0) ? 1 : 0;
                temp >>= 1;
                if (itest == 0) exp += 1;
            }
            int xmaxc = add(xmax >> (exp + 5), exp << 3);
            int[] em = xmaxcToExpMant(xmaxc);
            int rexp = em[0], mant = em[1];
            int temp1 = 6 - rexp;
            int temp2 = NRFAC[mant];
            int[] xMc = new int[13];
            for (int i = 0; i < 13; i++) {
                int t = w16(xM[i] << temp1);
                t = mult(t, temp2);
                xMc[i] = (t >> 12) + 4;
            }
            int[] xMp = apcmInverse(xMc, mant, rexp);
            int[] ep = gridPosition(Mc, xMp);
            return new int[][]{{xmaxc}, {Mc}, xMc, ep};
        }

        public int[][] encodeFrame(int[] samples) {
            int[] so = preprocess(samples);
            int[][] lr = lpc(so);
            so = lr[0];
            int[] LARc = lr[1];
            shortTermAnalysis(LARc, so);
            int[] dp = dp0;
            int[] Nc = new int[4], bc = new int[4], Mc = new int[4], xmaxc = new int[4], xmc = new int[52];
            for (int k = 0; k < 4; k++) {
                int base = 120 + 40 * k;
                int[] d = new int[40];
                System.arraycopy(so, k * 40, d, 0, 40);
                int[] bn = ltpParams(d, dp, base);
                int b = bn[0], n = bn[1];
                int BP = QLB[b];
                int[] dpp = new int[40];
                for (int i = 0; i < 40; i++) dpp[i] = multR(BP, dp[base + i - n]);
                int[] e = new int[50];
                for (int i = 0; i < 40; i++) e[5 + i] = sub(d[i], dpp[i]);
                int[][] rpe = rpeEncode(e);
                int xm = rpe[0][0], m = rpe[1][0];
                int[] xc = rpe[2], ep = rpe[3];
                for (int i = 0; i < 40; i++) dp[base + i] = add(ep[i], dpp[i]);
                Nc[k] = n; bc[k] = b; Mc[k] = m; xmaxc[k] = xm;
                System.arraycopy(xc, 0, xmc, k * 13, 13);
            }
            int[] ndp = new int[280];
            System.arraycopy(dp, 160, ndp, 0, 120);
            dp0 = ndp;
            return new int[][]{LARc, Nc, bc, Mc, xmaxc, xmc};
        }
    }

    // ------------------------------------------------------------- Decoder
    public static final class Decoder {
        private int[] dp0 = new int[280];
        private int[] v = new int[9];
        private int[][] LARpp = {new int[8], new int[8]};
        private int j = 0;
        private int nrp = 40;
        private int msr = 0;

        public int[] decodeFrame(int[][] params) {
            int[] LARc = params[0], Nc = params[1], bc = params[2], Mc = params[3],
                    xmaxc = params[4], xmc = params[5];
            int[] dp = dp0;
            int[] wt = new int[160];
            for (int jj = 0; jj < 4; jj++) {
                int[] em = xmaxcToExpMant(xmaxc[jj]);
                int exp = em[0], mant = em[1];
                int[] sub13 = new int[13];
                System.arraycopy(xmc, jj * 13, sub13, 0, 13);
                int[] erp = gridPosition(Mc[jj], apcmInverse(sub13, mant, exp));
                int Ncr = Nc[jj];
                int Nr = (Ncr < 40 || Ncr > 120) ? nrp : Ncr;
                nrp = Nr;
                int brp = QLB[bc[jj]];
                for (int k = 0; k < 40; k++)
                    dp[120 + k] = add(erp[k], multR(brp, dp[120 + k - Nr]));
                System.arraycopy(dp, 120, wt, jj * 40, 40);
                System.arraycopy(dp, 40, dp, 0, 120);
            }
            int[] cur = decodeLar(LARc);
            LARpp[j] = cur;
            j ^= 1;
            int[] prev = LARpp[j];
            int[][] rps = interpSets(prev, cur);
            int[] out = new int[160];
            for (int seg = 0; seg < 4; seg++) {
                int[] rrp = rps[seg];
                int start = SEG_START[seg], n = SEG_LEN[seg];
                for (int idx = start; idx < start + n; idx++) {
                    int sri = wt[idx];
                    for (int i = 7; i >= 0; i--) {
                        int t1 = rrp[i];
                        sri -= (t1 * v[i] + 16384) >> 15;
                        if (sri > MAX_WORD) sri = MAX_WORD; else if (sri < MIN_WORD) sri = MIN_WORD;
                        int t = ((t1 * sri + 16384) >> 15) + v[i];
                        if (t > MAX_WORD) t = MAX_WORD; else if (t < MIN_WORD) t = MIN_WORD;
                        v[i + 1] = t;
                    }
                    v[0] = sri;
                    out[idx] = sri;
                }
            }
            int msr = this.msr;
            for (int k = 0; k < 160; k++) {
                msr = add(out[k], multR(msr, 28180));
                out[k] = w16(add(msr, msr) & 0xFFF8);
            }
            this.msr = msr;
            dp0 = dp;
            return out;
        }
    }

    // ------------------------------------------------------- public codec
    private final Encoder enc = new Encoder();
    private final Decoder dec = new Decoder();

    public byte[] encodeBlock(int[] pcm320) {
        int[] f1 = new int[160], f2 = new int[160];
        System.arraycopy(pcm320, 0, f1, 0, 160);
        System.arraycopy(pcm320, 160, f2, 0, 160);
        return packBlock(enc.encodeFrame(f1), enc.encodeFrame(f2));
    }

    public short[] decodeBlock(byte[] data) {
        int[][][] frames = unpackBlock(data);
        int[] a = dec.decodeFrame(frames[0]);
        int[] b = dec.decodeFrame(frames[1]);
        short[] out = new short[320];
        for (int i = 0; i < 160; i++) out[i] = (short) a[i];
        for (int i = 0; i < 160; i++) out[160 + i] = (short) b[i];
        return out;
    }

    public byte[] encodePacket(short[] pcm1600) {
        byte[] out = new byte[PACKET_BYTES];
        int[] block = new int[320];
        for (int blk = 0; blk < PACKET_BLOCKS; blk++) {
            for (int i = 0; i < 320; i++) block[i] = pcm1600[blk * 320 + i];
            byte[] enc = encodeBlock(block);
            System.arraycopy(enc, 0, out, blk * BLOCK_BYTES, BLOCK_BYTES);
        }
        return out;
    }

    public short[] decodePacket(byte[] data) {
        short[] out = new short[PACKET_SAMPLES];
        byte[] blk = new byte[BLOCK_BYTES];
        for (int b = 0; b < PACKET_BLOCKS; b++) {
            System.arraycopy(data, b * BLOCK_BYTES, blk, 0, BLOCK_BYTES);
            short[] pcm = decodeBlock(blk);
            System.arraycopy(pcm, 0, out, b * BLOCK_SAMPLES, BLOCK_SAMPLES);
        }
        return out;
    }
}
