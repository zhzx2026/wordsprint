import com.aidemo.wordsprint.QREnc;
import java.io.*;
public class Repro {
  public static void main(String[] a) throws Exception {
    java.util.Random rnd = new java.util.Random(42);
    int[] lens = {1, 10, 13, 14, 15, 25, 26, 40, 60, 83, 84, 85, 120, 213, 214, 300, 500};
    for (int len : lens) {
      for (int t = 0; t < 6; t++) {
        byte[] raw = new byte[len]; rnd.nextBytes(raw);
        String code = "WPX1." + T.b64(raw);
        boolean[][] m = QREnc.encode(code.getBytes("UTF-8"), -1);
        if (code.getBytes("UTF-8").length == 116 && t == 0) {
          StringBuilder sb = new StringBuilder();
          for (boolean[] row : m) { for (boolean x : row) sb.append(x?'1':'0'); sb.append('\n'); }
          new PrintWriter("mine116.txt").append(sb.toString()).close();
        }
      }
      // dump the 120-len case's first? just dump all lengths where fail happens later
    }
    System.out.println("dumped");
  }
}
