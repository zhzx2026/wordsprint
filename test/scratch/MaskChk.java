import com.aidemo.wordsprint.QREnc;
import java.io.*; import java.util.*;
public class MaskChk {
  public static void main(String[] a) throws Exception {
    List<String> codes=new ArrayList<>();
    try(BufferedReader r=new BufferedReader(new InputStreamReader(new FileInputStream("sweep_codes.txt"),"UTF-8"))){String s;while((s=r.readLine())!=null)codes.add(s);}
    String code=codes.get(Integer.parseInt(a[0]));
    int mask=Integer.parseInt(a[1]);
    boolean[][] m=QREnc.encode(code.getBytes("UTF-8"),mask);
    int n=m.length;
    // unmask by same rule via re-encode with mask -? simpler: compare matrices across masks: cell(x,y) where x%3==0 and non-func should differ from mask0
    // just print format + verify with own logic is complex; instead compare m vs encode(mask0) cellwise: cells that differ should be exactly those with x%3==0 in DATA area under mask0? no.
    // Simplest: print whether zxing self-check style works: skip. Print row 6 (should be identical function row across masks).
    boolean[][] m0=QREnc.encode(code.getBytes("UTF-8"),0);
    int diff=0; int bad=0;
    for(int y=0;y<n;y++)for(int x=0;x<n;x++)if(m[y][x]!=m0[y][x]){diff++; if(x%3==0 && m0[y][x]==m[y][x])bad++;}
    System.out.println("cells diff vs mask0: "+diff+" (mask2 flips cells where x%3==0)");
  }
}
