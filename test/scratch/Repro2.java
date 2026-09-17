import com.aidemo.wordsprint.QREnc;
import java.io.*;
public class Repro2 {
  public static void main(String[] a) throws Exception {
    java.util.Random rnd = new java.util.Random(42);
    int[] lens = {1, 10, 13, 14, 15, 25, 26, 40, 60, 83, 84, 85, 120, 213, 214, 300, 500};
    PrintWriter out = new PrintWriter("cases.txt");
    for (int len : lens) {
      for (int t = 0; t < 6; t++) {
        byte[] raw = new byte[len]; rnd.nextBytes(raw);
        String code = "WPX1." + T.b64(raw);
        out.println(code);
      }
    }
    out.close();
    System.out.println("cases written: " + new File("cases.txt").length());
  }
}
