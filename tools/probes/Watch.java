import android.content.Context;
import java.lang.reflect.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Pattern;

/**
 * READ-ONLY watcher. Polls every readable state id (never *_SET) whose name matches a regex and prints each change
 * with a timestamp, so what a native BYD screen does to the HAL can be seen. Ids that change by themselves during a
 * short baseline (speed, clocks, temperatures...) are dropped. args: <regex> <seconds> [sweepMs]
 */
public class Watch {
    static final String[][] DEVICES = {
        {"Ac", "android.hardware.bydauto.ac.BYDAutoAcDevice", "1000"},
        {"Bodywork", "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice", "1001"},
        {"Audio", "android.hardware.bydauto.audio.BYDAutoAudioDevice", "1002"},
        {"Light", "android.hardware.bydauto.light.BYDAutoLightDevice", "1004"},
        {"Setting", "android.hardware.bydauto.setting.BYDAutoSettingDevice", "1023"},
    };

    public static void main(String[] args) throws Exception {
        android.os.Looper.prepareMainLooper();
        Pattern p = Pattern.compile(args[0], Pattern.CASE_INSENSITIVE);
        long seconds = Long.parseLong(args[1]);
        long sweepMs = args.length > 2 ? Long.parseLong(args[2]) : 400;
        Class<?> at = Class.forName("android.app.ActivityThread");
        Context ctx = (Context) at.getMethod("getSystemContext").invoke(at.getMethod("systemMain").invoke(null));
        Class<?> ids = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds");
        Method get = Class.forName("android.hardware.bydauto.AbsBYDAutoDevice").getDeclaredMethod("get", int.class, int.class);
        get.setAccessible(true);

        List<Object[]> watched = new ArrayList<>(); // [label, device, type, id, last]
        for (String[] d : DEVICES) {
            Object dev = Class.forName(d[1]).getMethod("getInstance", Context.class).invoke(null, ctx);
            for (Class<?> h : ids.getDeclaredClasses()) {
                if (!h.getSimpleName().equals(d[0])) continue;
                for (Field f : h.getDeclaredFields()) {
                    String n = f.getName();
                    if (!Modifier.isStatic(f.getModifiers()) || f.getType() != int.class || !p.matcher(n).find()) continue;
                    if (n.endsWith("_SET") || n.contains("hal_only") || n.contains("RCS_") || n.contains("_RSE_") || n.contains("_SET_")) continue;
                    int id = f.getInt(null);
                    if (id == 0) continue;
                    watched.add(new Object[]{d[0] + "." + n, dev, Integer.parseInt(d[2]), id, null});
                }
            }
        }
        System.out.println("watching " + watched.size() + " ids; baseline 5s to drop self-changing ones...");
        System.out.flush();

        Set<String> noisy = new HashSet<>();
        long t0 = System.currentTimeMillis();
        long sweepTime = 0;
        while (System.currentTimeMillis() - t0 < 5000) {
            long s = System.currentTimeMillis();
            for (Object[] w : watched) {
                Object v = read(get, w);
                if (w[4] != null && !w[4].equals(v)) noisy.add((String) w[0]);
                w[4] = v;
            }
            sweepTime = System.currentTimeMillis() - s;
            Thread.sleep(200);
        }
        List<Object[]> live = new ArrayList<>();
        for (Object[] w : watched) if (!noisy.contains((String) w[0])) live.add(w);
        System.out.println("sweep takes ~" + sweepTime + "ms; dropped " + noisy.size() + " self-changing ids: " + noisy);
        System.out.println("NOW WATCHING " + live.size() + " ids for " + seconds + "s — operate the native screens");
        System.out.flush();

        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS");
        long end = System.currentTimeMillis() + seconds * 1000;
        while (System.currentTimeMillis() < end) {
            for (Object[] w : live) {
                Object v = read(get, w);
                if (!v.equals(w[4])) {
                    System.out.println(fmt.format(new Date()) + "  " + w[0] + "  " + w[4] + " -> " + v + "   (id " + w[3] + ")");
                    System.out.flush();
                    w[4] = v;
                }
            }
            Thread.sleep(sweepMs);
        }
        System.out.println("done");
        System.exit(0);
    }

    static Object read(Method get, Object[] w) {
        try { return get.invoke(w[1], w[2], w[3]); } catch (Throwable t) { return "ERR"; }
    }
}
