import com.aidemo.wordsprint.QREnc; import com.aidemo.wordsprint.QRUtil;
import com.google.zxing.*; import com.google.zxing.common.HybridBinarizer; import com.google.zxing.qrcode.QRCodeReader;
import java.io.*; import java.util.*;
public class Chk {
  public static void main(String[] a) throws Exception {
    List<String> codes=new ArrayList<>(); try(BufferedReader r=new BufferedReader(new InputStreamReader(new FileInputStream("cases.txt"),"UTF-8"))){String s;while((s=r.readLine())!=null)codes.add(s);}
    byte[] p=codes.get(61).getBytes("UTF-8");
    for (int m=0;m<8;m++){
      boolean[][] mat=QREnc.encode(p,m);
      boolean okSelf=QRUtil.selfDecodes(mat);
      // scale4 render
      int n=mat.length,s=4,q=16,size=n*s+2*q; int[] px=new int[size*size]; Arrays.fill(px,0xFFFFFFFF);
      for(int y=0;y<n;y++)for(int x=0;x<n;x++)if(mat[y][x])for(int dy=0;dy<s;dy++)for(int dx=0;dx<s;dx++)px[(y*s+q+dy)*size+x*s+q+dx]=0xFF000000;
      boolean ok4=false;
      try{ new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(size,size,px))), Collections.singletonMap(DecodeHintType.TRY_HARDER,true)); ok4=true; }catch(Exception e){}
      System.out.println("mask"+m+" self="+okSelf+" scale4="+ok4);
    }
  }
}
