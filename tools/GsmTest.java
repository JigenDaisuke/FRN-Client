import net.voipitalia.frnlite.gsm.Gsm;
import java.io.BufferedReader;
import java.io.FileReader;

/** Verifies the Java GSM codec bit-exactly against vectors from the Python codec. */
public class GsmTest {
    public static void main(String[] args) throws Exception {
        BufferedReader r = new BufferedReader(new FileReader(args[0]));
        int P = Integer.parseInt(r.readLine().trim());
        Gsm encCodec = new Gsm();
        Gsm decCodec = new Gsm();
        int encFails = 0, decFails = 0;
        for (int p = 0; p < P; p++) {
            short[] pcmIn = parseShorts(r.readLine(), Gsm.PACKET_SAMPLES);
            byte[] encExp = parseHex(r.readLine());
            short[] pcmOutExp = parseShorts(r.readLine(), Gsm.PACKET_SAMPLES);

            byte[] encGot = encCodec.encodePacket(pcmIn);
            if (!eq(encGot, encExp)) {
                encFails++;
                System.out.println("ENC mismatch packet " + p + " at byte " + firstDiff(encGot, encExp));
            }
            short[] pcmGot = decCodec.decodePacket(encExp);
            if (!eq(pcmGot, pcmOutExp)) {
                decFails++;
                System.out.println("DEC mismatch packet " + p + " at sample " + firstDiff(pcmGot, pcmOutExp));
            }
        }
        System.out.println("packets=" + P + " encFails=" + encFails + " decFails=" + decFails);
        if (encFails == 0 && decFails == 0) System.out.println("ALL BIT-EXACT");
        else System.exit(1);
    }

    static short[] parseShorts(String line, int n) {
        String[] t = line.trim().split("\\s+");
        short[] out = new short[n];
        for (int i = 0; i < n; i++) out[i] = (short) Integer.parseInt(t[i]);
        return out;
    }

    static byte[] parseHex(String line) {
        line = line.trim();
        byte[] out = new byte[line.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(line.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    static boolean eq(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }

    static boolean eq(short[] a, short[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }

    static int firstDiff(byte[] a, byte[] b) {
        for (int i = 0; i < Math.min(a.length, b.length); i++) if (a[i] != b[i]) return i;
        return -1;
    }

    static int firstDiff(short[] a, short[] b) {
        for (int i = 0; i < Math.min(a.length, b.length); i++)
            if (a[i] != b[i]) { System.out.println("  got=" + a[i] + " exp=" + b[i]); return i; }
        return -1;
    }
}
