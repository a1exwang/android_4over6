package wang.a1ex.android_4over6;

/**
 * Created by alexwang on 3/27/16.
 */
public class NdkUtil {
    static {
        System.loadLibrary("ndkutil");
    }
    public native String getString();
}
