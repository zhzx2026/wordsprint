import com.aidemo.wordsprint.Pack;
import java.io.*;
public class Books {
  public static void main(String[] a) throws Exception {
    Pack.Data d;
    try(InputStream is=new BufferedInputStream(new FileInputStream("res/raw/wdb.dat"))){ d=Pack.read(is); }
    int total=0; java.util.Map<Integer,int[]> byStage=new java.util.TreeMap<>();
    for(Pack.Book b:d.books) {
      System.out.printf("%-10s | %-22s | stage=%d | n=%d | 例:%s%n",
        b.pub, b.title+" "+b.series, b.stage, b.n, d.pool[b.wI[0]]+"="+d.pool[b.mI[0]]);
      total+=b.n;
      int[] s=byStage.computeIfAbsent(b.stage,k->new int[2]); s[0]++; s[1]+=b.n;
    }
    // 文档里的「N 本词书 / M 个单词」就是这两行输出的数字，别再手写猜
    byStage.forEach((st,s)->System.out.println("  stage "+st+": "+s[0]+" 本 / "+s[1]+" 词"));
    System.out.println("books="+d.books.size()+" words="+total+" pool="+d.pool.length);
  }
}
