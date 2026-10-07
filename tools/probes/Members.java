import java.lang.reflect.*;
import java.util.*;
import java.util.regex.Pattern;

/** READ-ONLY: static int constants (with values) and method signatures of a class whose names match a regex. args: class regex */
public class Members {
    public static void main(String[] args) {
        try {
            Class<?> c = Class.forName(args[0]);
            Pattern p = Pattern.compile(args[1], Pattern.CASE_INSENSITIVE);
            List<String> out = new ArrayList<>();
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (p.matcher(f.getName()).find() && Modifier.isStatic(f.getModifiers())) {
                        f.setAccessible(true);
                        out.add("const  " + k.getSimpleName() + "." + f.getName() + " = " + f.get(null));
                    }
                }
                for (Method m : k.getDeclaredMethods()) {
                    if (p.matcher(m.getName()).find()) out.add("method " + k.getSimpleName() + "." + m.toGenericString().replaceAll("android\\.hardware\\.bydauto\\.", ""));
                }
            }
            Collections.sort(out);
            for (String s : out) System.out.println(s);
        } catch (Throwable t) { System.out.println("FAILED " + t); }
        System.exit(0);
    }
}
