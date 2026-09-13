import com.aidemo.wordsprint.QREnc;
import com.google.zxing.*; import com.google.zxing.qrcode.QRCodeReader;
import java.io.*; import java.util.*; import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
public class One2 {
  public static void main(String[] a) throws Exception {
    List<String> codes=new ArrayList<>(); try(BufferedReader r=new BufferedReader(new InputStreamReader(new FileInputStream("cases.txt"),"UTF-8"))){String s;while((s=r.readLine())!=null)codes.add(s);}
    String code=codes.get(Integer.parseInt(a[0]));
    int mask=Integer.parseInt(a[1]);
    boolean[][] m=QREnc.encode(code.getBytes("UTF-8"),mask);
    BufferedImage img=render(m);
    ImageIO.write(img,"PNG",new File("m"+mask+".png"));
    int n=img.getWidth(); int[] px=img.getRGB(0,0,n,n,null,0,n);
    BinaryBitmap bb=new BinaryBitmap(new com.google.zxing.common.HybridBinarizer(new RGBLuminanceSource(n,n,px)));
    try{ Result rr=new QRCodeReader().decode(bb, Collections.singletonMap(DecodeHintType.TRY_HARDER,true)); System.out.println("mask"+mask+" decode OK eq="+(rr.getText().equals(code))); }
    catch(Exception e){ System.out.println("mask"+mask+" -> "+e); }
  }
  static BufferedImage render(boolean[][] m){ int n=m.length,s=4,q=16,size=n*s+2*q; BufferedImage img=new BufferedImage(size,size,BufferedImage.TYPE_INT_RGB);
    for(int y=0;y<size;y++)for(int x=0;x<size;x++)img.setRGB(x,y,0xFFFFFF);
    for(int y=0;y<n;y++)for(int x=0;x<n;x++)if(m[y][x])for(int dy=0;dy<s;dy++)for(int dx=0;dx<s;dx++)img.setRGB(x*s+q+dx,y*s+q+dy,0);
    return img; }
}
