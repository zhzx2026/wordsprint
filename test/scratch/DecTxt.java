import com.google.zxing.*; import com.google.zxing.qrcode.QRCodeReader;
import java.awt.image.BufferedImage; import java.io.*; import java.util.*;
public class DecTxt {
  public static void main(String[] a) throws Exception {
    List<String> ls; try(BufferedReader r=new BufferedReader(new FileReader(a[0]))){ ls=new ArrayList<>(); String s; while((s=r.readLine())!=null)ls.add(s);}
    int n=ls.get(0).length(); int s=4,q=16; BufferedImage img=new BufferedImage(n*s+2*q,n*s+2*q,BufferedImage.TYPE_INT_RGB);
    for(int y=0;y<n*s+2*q;y++)for(int x=0;x<n*s+2*q;x++)img.setRGB(x,y,0xFFFFFF);
    for(int y=0;y<n;y++)for(int x=0;x<n;x++){ if(ls.get(y).charAt(x)=='1') for(int dy=0;dy<s;dy++)for(int dx=0;dx<s;dx++) img.setRGB(x*s+q+dx,y*s+q+dy,0); }
    int[] px=img.getRGB(0,0,n,n,null,0,n);
    BinaryBitmap bb=new BinaryBitmap(new com.google.zxing.common.HybridBinarizer(new RGBLuminanceSource(n,n,px)));
    try{ Result rr=new QRCodeReader().decode(bb, Collections.singletonMap(DecodeHintType.TRY_HARDER,true));
      System.out.println(a[0]+" -> decoded len "+rr.getText().length()); }
    catch(Exception e){ System.out.println(a[0]+" -> "+e); }
  }
}
