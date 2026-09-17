import com.google.zxing.*; import com.google.zxing.qrcode.QRCodeReader; import javax.imageio.ImageIO; import java.io.*; import java.util.*;
public class DecPng { public static void main(String[] a) throws Exception {
  BufferedImage0.run(a[0]);
}}
class BufferedImage0 { static void run(String p) throws Exception {
  java.awt.image.BufferedImage img=ImageIO.read(new File(p));
  int n=img.getWidth(),m=img.getHeight(); int[] px=img.getRGB(0,0,n,m,null,0,n);
  BinaryBitmap bb=new BinaryBitmap(new com.google.zxing.common.HybridBinarizer(new RGBLuminanceSource(n,m,px)));
  try{ Result rr=new QRCodeReader().decode(bb, Collections.singletonMap(DecodeHintType.TRY_HARDER,true)); System.out.println(p+" -> OK len "+rr.getText().length()); }catch(Exception e){ System.out.println(p+" -> "+e);}
}}
