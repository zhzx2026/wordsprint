import java.util.Base64;
public class T {
    public static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    public static byte[] unb64(String s) { return Base64.getUrlDecoder().decode(s); }
}
