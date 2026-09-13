import com.aidemo.wordsprint.Pack;
import java.io.*;
public class Books {
  public static void main(String[] a) throws Exception {
    Pack.Data d;
    try(InputStream is=new BufferedInputStream(new FileInputStream("res/raw/wdb.dat"))){ d=Pack.read(is); }
    for(Pack.Book b:d.books) System.out.printf("%-10s | %-22s | n=%d | 例:%s%n",
      b.pub, b.title+" "+b.series, b.n, d.pool[b.wI[0]]+"="+d.pool[b.mI[0]]);
    System.out.println("books="+d.books.size());
  }
}
