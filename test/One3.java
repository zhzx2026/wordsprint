import com.aidemo.wordsprint.QREnc;
import java.io.*; import java.util.*;
public class One3 {
  public static void main(String[] a) throws Exception {
    List<String> codes=new ArrayList<>();
    try(BufferedReader r=new BufferedReader(new InputStreamReader(new FileInputStream("sweep_codes.txt"),"UTF-8"))){String s;while((s=r.readLine())!=null)codes.add(s);}
    String code=codes.get(Integer.parseInt(a[0]));
    int mask=Integer.parseInt(a[1]);
    boolean[][] m=QREnc.encode(code.getBytes("UTF-8"),mask);
    StringBuilder sb=new StringBuilder();
    for(boolean[] row:m){for(boolean x:row)sb.append(x?'1':'0');sb.append('\n');}
    try(PrintWriter pw=new PrintWriter(new OutputStreamWriter(new FileOutputStream("o3.txt"),"UTF-8"))) {pw.print(sb);}
    System.out.println("size "+m.length+" codeLen "+code.getBytes("UTF-8").length);
  }
}
