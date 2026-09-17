import com.aidemo.wordsprint.QREnc; import com.aidemo.wordsprint.QRUtil;
import com.google.zxing.*; import com.google.zxing.common.HybridBinarizer; import com.google.zxing.qrcode.QRCodeReader;
import java.io.*; import java.util.*;
public class Sweep2 {
  public static void main(String[] a) throws Exception {
    List<String> codes=new ArrayList<>();
    try(BufferedReader r=new BufferedReader(new InputStreamReader(new FileInputStream("sweep_codes.txt"),"UTF-8"))){String s;while((s=r.readLine())!=null)codes.add(s);}
    com.google.zxing.Reader rd=new QRCodeReader();
    Map<DecodeHintType,Object> hints=Collections.singletonMap(DecodeHintType.TRY_HARDER,(Object)true);
    int fails=0;
    for (int i=0;i<codes.size();i++) {
      String code=codes.get(i);
      byte[] payload=code.getBytes("UTF-8");
      // test BOTH: auto-penalty mask AND verified
      for (int mode=0;mode<2;mode++){
        boolean[][] m = mode==0 ? QREnc.encode(payload) : QRUtil.verifiedEncode(payload);
        boolean ok=false;
        try{
          int n=m.length,s=4,q=4*s,size=n*s+2*q; int[] px=new int[size*size]; Arrays.fill(px,0xFFFFFFFF);
          for(int y=0;y<n;y++)for(int x=0;x<n;x++)if(m[y][x])for(int dy=0;dy<s;dy++)for(int dx=0;dx<s;dx++)px[(y*s+q+dy)*size+x*s+q+dx]=0xFF000000;
          Result rr=rd.decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(size,size,px))),hints);
          ok=code.equals(rr.getText());
        }catch(Exception e){}
        if(!ok){fails++; System.out.println("FAIL i="+i+" len="+payload.length+" mode="+mode);}
      }
    }
    System.out.println(fails==0?("SWEEP ALL "+codes.size()*2+" DECODES OK"):fails+" FAILURES");
  }
}
