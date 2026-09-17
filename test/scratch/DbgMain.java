import com.aidemo.wordsprint.QREnc;
public class DbgMain { public static void main(String[] a) throws Exception {
  String s = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("case54.txt")),"UTF-8").trim();
  System.out.println(QREnc.dbgCodewords(6, s.getBytes("UTF-8")));
}}
