import com.aidemo.wordsprint.QREnc;
import com.google.zxing.*; import com.google.zxing.common.HybridBinarizer; import com.google.zxing.qrcode.QRCodeReader;
import java.awt.image.BufferedImage; import java.util.*;
public class One {
  public static void main(String[] a) throws Exception {
    boolean[][] m = QREnc.encode(a[0].getBytes("UTF-8"), Integer.parseInt(a[1]));
    BufferedImage img = render(m, 4);
    int w=img.getWidth(), h=img.getHeight();
    int[] px = img.getRGB(0,0,w,h,null,0,w);
    BinaryBitmap bb = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(w,h,px)));
    Result r = new QRCodeReader().decode(bb, Collections.singletonMap(DecodeHintType.TRY_HARDER,true));
    System.out.println("decoded=[" + r.getText() + "] equals=" + a[0].equals(r.getText()));
  }
  static BufferedImage render(boolean[][] m, int s) {
    int n=m.length, size=n*s+8*s;
    BufferedImage img=new BufferedImage(size,size,BufferedImage.TYPE_INT_RGB);
    for(int y=0;y<size;y++)for(int x=0;x<size;x++)img.setRGB(x,y,0xFFFFFF);
    for(int y=0;y<n;y++)for(int x=0;x<n;x++)if(m[y][x])for(int dy=0;dy<s;dy++)for(int dx=0;dx<s;dx++)img.setRGB(x*s+4*s+dx,y*s+4*s+dy,0);
    return img;
  }
}
