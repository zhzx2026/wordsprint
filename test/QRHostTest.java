import com.aidemo.wordsprint.QREnc;
import com.aidemo.wordsprint.Transfer;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;

public class QRHostTest {
    static BufferedImage render(boolean[][] m, int scale) {
        int n = m.length, size = n * scale + 8 * scale;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) img.setRGB(x, y, 0xFFFFFF);
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) if (m[y][x])
            for (int dy = 0; dy < scale; dy++) for (int dx = 0; dx < scale; dx++)
                img.setRGB(x * scale + 4 * scale + dx, y * scale + 4 * scale + dy, 0x000000);
        return img;
    }
    static String zxingDecode(BufferedImage img) throws Exception {
        int w = img.getWidth(), h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        LuminanceSource src = new RGBLuminanceSource(w, h, px);
        BinaryBitmap bb = new BinaryBitmap(new HybridBinarizer(src));
        return new QRCodeReader().decode(bb, Collections.singletonMap(DecodeHintType.TRY_HARDER, true)).getText();
    }
    static void check(boolean c, String m) { if (!c) throw new RuntimeException("FAIL: " + m); }

    public static void main(String[] a) throws Exception {
        Random rnd = new Random(42);
        int[] lens = {1, 10, 13, 14, 15, 25, 26, 40, 60, 83, 84, 85, 120, 213, 214, 300, 500, 700, 1000, 1250, 1800, 2200, 2329};
        int pass = 0;
        for (int len : lens) {
            for (int t = 0; t < (len > 200 ? 2 : 6); t++) {
                byte[] raw = new byte[len];
                rnd.nextBytes(raw);
                String code = "WPX1." + T.b64(raw);
                byte[] payload = code.getBytes("UTF-8");
                if (payload.length > 2331) continue; // QR-M cap
                boolean[][] mat = com.aidemo.wordsprint.QRUtil.verifiedEncode(payload);
                BufferedImage img = render(mat, 4);
                String got;
                try { got = zxingDecode(img); } catch (Exception e) {
                    File f = new File("qr_fail_" + payload.length + "_" + t + ".png");
                    ImageIO.write(img, "png", f);
                    throw new RuntimeException("decode fail len=" + payload.length + " t=" + t + " " + e + " saved " + f);
                }
                check(code.equals(got), "mismatch len=" + payload.length + " t=" + t + "\n want " + code.substring(0, Math.min(60, code.length())) + "\n got  " + got.substring(0, Math.min(60, got.length())));
                pass++;
            }
        }
        System.out.println("QR zxing round-trip OK (" + pass + " cases)");

        // Transfer round-trip incl. bits & days
        List<Transfer.BookRec> bs = new ArrayList<>();
        bs.add(new Transfer.BookRec("a1b2c3d4e5", 50, bits(0, 50, 100)));
        bs.add(new Transfer.BookRec("ff00aa11bb", 0, bits(0, 7, 9)));
        List<Transfer.DayRec> ds = new ArrayList<>();
        ds.add(new Transfer.DayRec(20260912, 33)); ds.add(new Transfer.DayRec(20260913, 50));
        byte[] enc = Transfer.encode(Transfer.VER, bs, ds);
        Transfer.Decoded dec = Transfer.decode(enc);
        check(dec.books.size() == 2 && dec.days.size() == 2, "counts");
        check(dec.books.get(0).bookId.equals("a1b2c3d4e5") && dec.books.get(0).pos == 50, "book0");
        check(BitSet.valueOf(dec.books.get(0).bits).nextSetBit(49) == 49 && BitSet.valueOf(dec.books.get(0).bits).nextSetBit(50) == -1 && BitSet.valueOf(dec.books.get(0).bits).cardinality() == 50, "book0 bits");
        check(dec.days.get(0).date == 20260912 && dec.days.get(1).count == 50, "days");
        System.out.println("Transfer round-trip OK, payload=" + enc.length + "B");

        // end-to-end: realistic export string through QR
        BitSet sim = new BitSet();
        for (int i = 0; i < 137; i++) sim.set(i);
        List<Transfer.BookRec> b2 = new ArrayList<>();
        b2.add(new Transfer.BookRec("deadbeef01", 137, Transfer.packBits(sim, 137)));
        byte[] e2 = Transfer.encode(1, b2, ds);
        String code2 = "WPX1." + T.b64(e2);
        boolean[][] m2 = com.aidemo.wordsprint.QRUtil.verifiedEncode(code2.getBytes("UTF-8"));
        String got2 = zxingDecode(render(m2, 5));
        check(got2.equals(code2), "realistic round trip");
        Transfer.Decoded d2 = Transfer.decode(T.unb64(got2.substring(5)));
        check(BitSet.valueOf(d2.books.get(0).bits).cardinality() == 137 && d2.books.get(0).pos == 137, "realistic bits");
        System.out.println("Realistic export->QR->scan->merge pipeline OK (" + code2.length() + " chars)");
        System.out.println("ALL QR/TRANSFER TESTS PASS");
    }
    static byte[] bits(int from, int to, int n) { BitSet b = new BitSet(n); for (int i = from; i < to; i++) b.set(i); return b.toByteArray(); }
}
