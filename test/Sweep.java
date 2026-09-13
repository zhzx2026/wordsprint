import com.aidemo.wordsprint.QREnc;
import java.io.*; import java.util.*;
public class Sweep {
  public static void main(String[] a) throws Exception {
    PrintWriter out=new PrintWriter(new OutputStreamWriter(new FileOutputStream("sweep_codes.txt"),"UTF-8"));
    Random rnd=new Random(7);
    for (int len=1; len<=500; len++) {
      byte[] b=new byte[len]; rnd.nextBytes(b);
      StringBuilder sb=new StringBuilder("WPX1.");
      out.println(sb.toString()+T.b64(b));
    }
    out.close(); System.out.println("codes written");
  }
}
