import com.aidemo.wordsprint.QREnc;
public class DbgMain2 { public static void main(String[] a) throws Exception {
  for (String ln : java.nio.file.Files.readAllLines(java.nio.file.Paths.get(a[0]), java.nio.charset.StandardCharsets.UTF_8)) {
    if (ln.isEmpty()) continue;
    System.out.println("V " + a[1] + ": " + QREnc.dbgCodewords(Integer.parseInt(a[1]), ln.getBytes("UTF-8")));
  }
}}
