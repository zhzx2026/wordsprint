import com.aidemo.wordsprint.QREnc;
import com.google.zxing.*;
import com.google.zxing.qrcode.QRCodeReader;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

public class Cmp {
  public static void main(String[] a) throws Exception {
    com.google.zxing.Reader reader = new QRCodeReader();
    Map<DecodeHintType, Object> hints = Collections.singletonMap(DecodeHintType.TRY_HARDER, (Object) Boolean.TRUE);
    List<String> codes = new ArrayList<>();
    try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream("cases.txt"), "UTF-8"))) {
      String s; while ((s = r.readLine()) != null) codes.add(s);
    }
    int fail = 0;
    for (int i = 0; i < codes.size(); i++) {
      String code = codes.get(i); if (i==61) { boolean[][] mm2 = QREnc.encode(code.getBytes("UTF-8"), -1); StringBuilder s2=new StringBuilder(); for(boolean[] r0:mm2){for(boolean z:r0)s2.append(z?"1":"0");s2.append("\n");} System.out.println("CASE61 size="+mm2.length); try(PrintWriter pw=new PrintWriter("case61.txt")){pw.print(s2);} }
      boolean[][] m = QREnc.encode(code.getBytes("UTF-8"), -1);
      String t1 = null, t2 = null; Exception e1 = null, e2 = null;
      try { t1 = reader.decode(toBB(render(m)), hints).getText(); } catch (Exception e) { e1 = e; }
      try { t2 = reader.decode(toBB(ImageIO.read(new File("ref/r" + String.format("%03d", i) + ".png"))), hints).getText(); } catch (Exception e) { e2 = e; }
      boolean ok = code.equals(t1) && code.equals(t2);
      if (!ok) {
        fail++;
        String mineS = e1 != null ? "ERR:" + e1.getClass().getSimpleName() : (code.equals(t1) ? "OK" : "MISMATCH len" + (t1 == null ? -1 : t1.length()));
        String refS = e2 != null ? "ERR:" + e2.getClass().getSimpleName() : (code.equals(t2) ? "OK" : "MISMATCH");
        System.out.println("case " + i + " codeLen=" + code.getBytes("UTF-8").length + " mine=" + mineS + " ref=" + refS);
      }
    }
    System.out.println(fail == 0 ? "ALL " + codes.size() + " CASES OK" : fail + "/" + codes.size() + " FAIL");
  }

  static BinaryBitmap toBB(BufferedImage img) {
    int w = img.getWidth(), h = img.getHeight();
    int[] px = img.getRGB(0, 0, w, h, null, 0, w);
    return new BinaryBitmap(new com.google.zxing.common.HybridBinarizer(new RGBLuminanceSource(w, h, px)));
  }

  static BufferedImage render(boolean[][] m) {
    int n = m.length, s = 4, size = n * s + 8 * s;
    BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) img.setRGB(x, y, 0xFFFFFF);
    for (int y = 0; y < n; y++) for (int x = 0; x < n; x++)
      if (m[y][x]) for (int dy = 0; dy < s; dy++) for (int dx = 0; dx < s; dx++) img.setRGB(x * s + 4 * s + dx, y * s + 4 * s + dy, 0);
    return img;
  }
}
