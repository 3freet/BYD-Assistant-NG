import android.content.Context;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * READ-ONLY. Calls the public getters of one BYDAuto device class and prints what the car answers: every
 * method whose name starts with get/has/is and that takes no argument, one int (tried for 0..maxArg, which covers
 * the usual 1-4 wheel/door/seat positions) or one String (tried with the class's own FEATURE_* constants).
 * It never calls a setter, and skips the getters that ask the car to republish everything (getAllStatus).
 *   args: <class> [maxArg=4]
 */
public class Getters {
    static final Set<String> SKIP = new HashSet<>(Arrays.asList("getInstance", "getAllStatus", "getDevicetype", "getGetPermission", "getSetPermission"));

    public static void main(String[] args) {
        android.os.Looper.prepareMainLooper();
        try {
            Class<?> c = Class.forName(args[0]);
            int maxArg = args.length > 1 ? Integer.parseInt(args[1]) : 4;
            Class<?> at = Class.forName("android.app.ActivityThread");
            Context ctx = (Context) at.getMethod("getSystemContext").invoke(at.getMethod("systemMain").invoke(null));
            Object dev = c.getMethod("getInstance", Context.class).invoke(null, ctx);

            List<String> featureNames = new ArrayList<>();
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) && f.getType() == String.class && f.getName().startsWith("FEATURE_")) {
                    f.setAccessible(true);
                    featureNames.add((String) f.get(null));
                }
            }

            List<Method> methods = new ArrayList<>(Arrays.asList(c.getDeclaredMethods()));
            Collections.sort(methods, new Comparator<Method>() {
                public int compare(Method a, Method b) { return a.getName().compareTo(b.getName()); }
            });
            for (Method m : methods) {
                String n = m.getName();
                if (Modifier.isStatic(m.getModifiers()) || !Modifier.isPublic(m.getModifiers()) || SKIP.contains(n)) continue;
                if (!(n.startsWith("get") || n.startsWith("has") || n.startsWith("is"))) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 0) {
                    show(dev, m, n + "()", new Object[0]);
                } else if (p.length == 1 && p[0] == int.class) {
                    for (int a = 0; a <= maxArg; a++) show(dev, m, n + "(" + a + ")", new Object[]{a});
                } else if (p.length == 1 && p[0] == String.class) {
                    for (String s : featureNames) show(dev, m, n + "(\"" + s + "\")", new Object[]{s});
                }
            }
        } catch (Throwable t) { System.out.println("FAILED " + (t.getCause() != null ? t.getCause() : t)); }
        System.exit(0);
    }

    static void show(Object dev, Method m, String label, Object[] callArgs) {
        try {
            Object v = m.invoke(dev, callArgs);
            if (v instanceof int[]) v = Arrays.toString((int[]) v);
            else if (v instanceof double[]) v = Arrays.toString((double[]) v);
            else if (v instanceof float[]) v = Arrays.toString((float[]) v);
            System.out.println(String.format("%-52s = %s", label, v));
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            System.out.println(String.format("%-52s   failed: %s", label, c.getClass().getSimpleName()));
        }
    }
}
