import ke.co.pichalite.PichaRuntime;
import ke.co.pichalite.NativeJobs;

/** Run with -Xcheck:jni. Checks JNI registration, RPC and repeated lifecycle. */
public final class JniSmoke {
    public static void main(String[] args) {
        for (int generation = 0; generation < 2; generation++) {
            int result = PichaRuntime.nativeStart(new String[] {"--root", args[0]});
            if (result != 0) throw new AssertionError("start=" + result + ": " + PichaRuntime.nativeLastError());
            if (PichaRuntime.nativeStatus() != 2) throw new AssertionError("not running");
            String snapshot = NativeJobs.nativeCall("{\"action\":\"snapshot\"}");
            if (!snapshot.contains("\"ok\":true")) throw new AssertionError(snapshot);
            String pause = NativeJobs.nativeCall("{\"action\":\"pause\",\"paused\":true}");
            if (!pause.contains("\"paused\":true")) throw new AssertionError(pause);
            String malformed = NativeJobs.nativeCall("this is not JSON");
            if (!malformed.contains("\"ok\":false")) throw new AssertionError(malformed);
            int stopped = PichaRuntime.nativeStop(10_000);
            if (stopped != 0) throw new AssertionError("stop=" + stopped + ": " + PichaRuntime.nativeLastError());
            System.out.println("JNI generation " + generation + " passed");
        }
    }
}
