import android.content.Context;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.regex.Pattern;

/**
 * READ-ONLY. Lists controls in the car's own BYDAutoFeatureIds whose name matches a regex, with their real IDs, and
 * (for state ids, not *_SET) the value the car currently reports. Never calls a setter.
 *   args: <regex> [read]
 */
public class Feat {
    static final Map<String, String[]> DEVICES = new LinkedHashMap<>();
    static {
        DEVICES.put("Ac", new String[]{"android.hardware.bydauto.ac.BYDAutoAcDevice", "1000"});
        DEVICES.put("Bodywork", new String[]{"android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice", "1001"});
        DEVICES.put("Audio", new String[]{"android.hardware.bydauto.audio.BYDAutoAudioDevice", "1002"});
        DEVICES.put("Light", new String[]{"android.hardware.bydauto.light.BYDAutoLightDevice", "1004"});
        DEVICES.put("Setting", new String[]{"android.hardware.bydauto.setting.BYDAutoSettingDevice", "1023"});
        DEVICES.put("Tyre", new String[]{"android.hardware.bydauto.tyre.BYDAutoTyreDevice", "1016"});
        DEVICES.put("Instrument", new String[]{"android.hardware.bydauto.instrument.BYDAutoInstrumentDevice", "1007"});
    }

    public static void main(String[] args) {
        android.os.Looper.prepareMainLooper();
        try {
            Pattern p = Pattern.compile(args[0], Pattern.CASE_INSENSITIVE);
            boolean read = args.length > 1 && args[1].equals("read");
            Class<?> ids = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds");
            Class<?> at = Class.forName("android.app.ActivityThread");
            Context ctx = (Context) at.getMethod("getSystemContext").invoke(at.getMethod("systemMain").invoke(null));
            Map<String, Object[]> readers = new HashMap<>();
            List<Class<?>> holders = new ArrayList<>(Arrays.asList(ids.getDeclaredClasses()));
            holders.add(0, ids);
            for (Class<?> h : holders) {
                String group = h == ids ? "(top)" : h.getSimpleName();
                List<String> lines = new ArrayList<>();
                for (Field f : h.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) continue;
                    if (!p.matcher(f.getName()).find()) continue;
                    int id = f.getInt(null);
                    String line = String.format("%-9s %-62s %11d  0x%08X", group, f.getName(), id, id);
                    if (read && id != 0 && !f.getName().endsWith("_SET") && !f.getName().contains("hal_only") && DEVICES.containsKey(group)) {
                        try {
                            Object[] dev = readers.get(group);
                            if (dev == null) {
                                String[] d = DEVICES.get(group);
                                Class<?> dc = Class.forName(d[0]);
                                Object inst = dc.getMethod("getInstance", Context.class).invoke(null, ctx);
                                Method get = Class.forName("android.hardware.bydauto.AbsBYDAutoDevice").getDeclaredMethod("get", int.class, int.class);
                                get.setAccessible(true);
                                dev = new Object[]{inst, get, Integer.parseInt(d[1])};
                                readers.put(group, dev);
                            }
                            Object v = ((Method) dev[1]).invoke(dev[0], dev[2], id);
                            line += "   = " + v;
                        } catch (Throwable t) {
                            Throwable c = t.getCause() != null ? t.getCause() : t;
                            line += "   read failed: " + c.getClass().getSimpleName();
                        }
                    }
                    lines.add(line);
                }
                Collections.sort(lines);
                for (String l : lines) System.out.println(l);
            }
        } catch (Throwable t) { System.out.println("FAILED " + (t.getCause() != null ? t.getCause() : t)); }
        System.exit(0);
    }
}
