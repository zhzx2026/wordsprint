import java.io.*; import java.util.*;
public class ReadCW {
  // usage: ReadCW matrixfile size  -> prints codewords (pre-mask handled by passing mask)
  public static void main(String[] a) throws Exception {
    String[] lines = new BufferedReader(new InputStreamReader(new FileInputStream(a[0]),"UTF-8")).lines().toArray(String[]::new);
    int n = lines[0].length();
    int[][] mat = new int[n][n];
    for (int y=0;y<n;y++) for (int x=0;x<n;x++) mat[y][x]=lines[y].charAt(x)-'0';
    int mask = Integer.parseInt(a[1]);
    boolean[][] reserved = reserved(n);
    for (int y=0;y<n;y++)for(int x=0;x<n;x++){ if(x==6||y==6){reserved[y][x]=true;continue;} boolean d=maskFn(mask,x,y); if(d) mat[y][x]^=1; }
    List<Integer> bits=new ArrayList<>();
    int row=n-1,col=n-1,dir=-1;
    while(col>0){
      if(col==6)col--;
      for(int c=0;c<2;c++){ int x=col-c; if(!reserved[row][x]) bits.add(mat[row][x]); }
      row+=dir;
      if(row<0||row>=n){ dir=-dir; row+=dir; col-=2; if(col<=0)break; }
    }
    StringBuilder sb=new StringBuilder();
    int acc=0,cnt=0;
    for(int b:bits){ acc=(acc<<1)|b; if(++cnt==8){ sb.append(acc).append(' '); acc=0;cnt=0; } }
    System.out.println(sb.toString().trim());
  }
  static boolean[][] reserved(int n){
    boolean[][] r=new boolean[n][n];
    for(int oy=0;oy<=n-8;oy+=(n-8==0?1:n-8))for(int ox=0;ox<=n-8;ox+=(n-8==0?1:n-8)){}
    int[][] org={{0,0},{n-7,0},{0,n-7}};
    for(int[]o:org)for(int dy=-1;dy<=7;dy++)for(int dx=-1;dx<=7;dx++){int y=o[1]+dy,x=o[0]+dx;if(x>=0&&y>=0&&x<n&&y<n)r[y][x]=true;}
    for(int i=0;i<n;i++){r[6][i]=true;r[i][6]=true;}
    for(int i=0;i<=8;i++){if(i<n){r[8][i]=true;r[i][8]=true;}}
    for(int i=0;i<8;i++){r[8][n-1-i]=true;r[n-8+i][8]=true;}
    if(n>=37){List<Integer> ps=new ArrayList<>();int step=2,sp=16;while(true){sp+=step;if(n==21)break;ps.add(sp);if(sp+7>=n)break;sp+=7;step+=2;}Collections.sort(ps);ps.add(6);ps.add(n-7);for(int i=0;i<ps.size();i++)for(int j=0;j<ps.size();j++){int cx=ps.get(i),cy=ps.get(j);if((i==0&&j==0)||(i==0&&j==ps.size()-1)||(i==ps.size()-1&&j==0))continue;if(cx>=n||cy>=n)continue;for(int dy=-2;dy<=2;dy++)for(int dx=-2;dx<=2;dx++){int y=cy+dy,x=cx+dx;if(x>=0&&y>=0&&x<n&&y<n)r[y][x]=true;}}}
    if(n>=25){for(int i=0;i<18;i++){r[n-11+i/3][i%3]=true;r[i%3][n-11+i/3]=true;}}
    return r;
  }
  static boolean maskFn(int m,int x,int y){switch(m){case 0:return (x+y)%2==0;case 1:return y%2==0;case 2:return x%3==0;case 3:return (x+y)%3==0;case 4:return (y/2+x/3)%2==0;case 5:return (x*y)%2+(x*y)%3==0;case 6:return ((x*y)%2+(x*y)%3)%2==0;default:return ((x+y)%2+(x*y)%3)%2==0;}}
}
