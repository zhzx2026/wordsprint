import com.aidemo.wordsprint.QREnc;
public class Dump {
  public static void main(String[] a) throws Exception {
    int mask = Integer.parseInt(a[1]);
    boolean[][] m = QREnc.encode(a[0].getBytes("UTF-8"), mask);
    StringBuilder sb=new StringBuilder();
    for (boolean[] row : m) { for (boolean x : row) sb.append(x?'1':'0'); sb.append('\n'); }
    System.out.print(sb);
  }
}
