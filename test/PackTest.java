import com.aidemo.wordsprint.Pack;
import java.io.*;
import java.util.*;

public class PackTest {
    public static void main(String[] args) throws Exception {
        long t0 = System.currentTimeMillis();
        Pack.Data d;
        try (InputStream is = new BufferedInputStream(new FileInputStream("res/raw/wdb.dat"), 1 << 16)) {
            d = Pack.read(is);
        }
        long t1 = System.currentTimeMillis();
        System.out.println("parse ms=" + (t1 - t0) + " books=" + d.books.size() + " pool=" + d.pool.length);
        long words = 0; int empties = 0, badPh = 0;
        Set<String> ids = new HashSet<>();
        int ezBooks = 0;
        for (Pack.Book b : d.books) {
            if (!ids.add(b.id)) throw new RuntimeException("dup id " + b.id);
            if (b.n <= 0) throw new RuntimeException("empty book " + b.title);
            words += b.n;
            Set<String> ezWords = new HashSet<>();
            for (int i = 0; i < b.n; i++) {
                String w = d.pool[b.wI[i]], m = d.pool[b.mI[i]], p = d.pool[b.pI[i]];
                if (w == null || w.isEmpty() || m == null || m.isEmpty()) empties++;
                if (p.length() > 40) badPh++;
                if (!w.matches(".*[A-Za-z].*")) throw new RuntimeException("bad word in " + b.title + ": " + w);
                if (b.title.equals("3500词汇高频使用词汇（E–Z）")) ezWords.add(w.toLowerCase());
            }
            if (b.title.equals("3500词汇高频使用词汇（E–Z）")) {
                ezBooks++;
                if (!"高频词汇".equals(b.pub) || b.stage != 3 || b.n != 628)
                    throw new RuntimeException("bad E-Z book metadata/count: " + b.pub + " stage=" + b.stage + " n=" + b.n);
                for (String sample : new String[]{"ease", "side effect", "at random", "spiritual", "widespread"})
                    if (!ezWords.contains(sample)) throw new RuntimeException("E-Z book missing " + sample);
            }
        }
        if (ezBooks != 1) throw new RuntimeException("expected one E-Z book, found " + ezBooks);
        System.out.println("words=" + words + " empties=" + empties + " badPh=" + badPh + " E-Z=628");
        // spot check a known word
        for (Pack.Book b : d.books) {
            if (b.pub.contains("人教版") && b.title.contains("三年级上") && b.series.contains("三年级起点")) {
                System.out.println("PEP3A words=" + b.n);
                for (int i = 0; i < Math.min(6, b.n); i++)
                    System.out.println("  " + d.pool[b.wI[i]] + " | " + d.pool[b.pI[i]] + " | " + d.pool[b.mI[i]]);
                break;
            }
        }
        // publishers present?
        Set<String> pubs = new LinkedHashSet<>();
        for (Pack.Book b : d.books) pubs.add(b.pub);
        System.out.println("pubs(" + pubs.size() + "): " + pubs);
        System.out.println("PACK OK");
    }
}
