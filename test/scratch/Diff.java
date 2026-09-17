import com.aidemo.wordsprint.QREnc;
import java.io.*; import java.util.*;
public class Diff {
  public static void main(String[] a) throws Exception {
    List<String> codes = new ArrayList<>();
    BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream("cases.txt"),"UTF-8"));
    String s; while((s=r.readLine())!=null) codes.add(s); r.close();
    String code = codes.get(Integer.parseInt(a[0]));
    boolean[][] m = QREnc.encode(code.getBytes("UTF-8"), 0);
    StringBuilder sb=new StringBuilder();
    for (boolean[] row : m) { for (boolean x : row) sb.append(x?'1':'0'); sb.append('\n'); }
    try(PrintWriter pw=new PrintWriter("mymat.txt")){pw.print(sb.toString());}
    System.out.println("size="+m.length+" codeBytes="+code.getBytes("UTF-8").length);
    System.out.println("payload bits check: need cw="+((code.getBytes("UTF-8").length*8+12+7)/8));
  }
}
