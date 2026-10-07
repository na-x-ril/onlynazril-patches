import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.MultiDexContainer;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.Reference;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Where does TikTok build its Typefaces?
 *
 *   tools/fontprobe/run.sh "<apk>" factories|class:<name>|hubs
 *
 *   factories: every method the APK defines that returns a Typeface,
 *              with how many callers it has, sorted busiest first.
 *   hubs:      per class, how many Typeface-method invokes it makes.
 *   class:<n>: dump one class's Typeface-returning methods.
 *
 * The patcher can only hook classes inside the APK, so an app-wide
 * font needs a factory the app itself routes through.
 */
public class FontProbe {
    private static final String TYPEFACE = "Landroid/graphics/Typeface;";

    public static void main(String[] args) throws Exception {
        String apk = args[0];
        String mode = args.length > 1 ? args[1] : "factories";

        MultiDexContainer<? extends DexBackedDexFile> container =
                DexFileFactory.loadDexContainer(new File(apk), Opcodes.getDefault());
        System.out.println("apk=" + apk + "  dex entries=" + container.getDexEntryNames().size());

        // factories: key -> {owner, method, static}
        Map<String, Method> factories = new TreeMap<>();
        // callers: method key -> count
        Map<String, Integer> callers = new HashMap<>();
        // per-class Typeface-invoke counts
        Map<String, Integer> hubs = new HashMap<>();

        for (String entryName : container.getDexEntryNames()) {
            DexBackedDexFile dex = container.getEntry(entryName).getDexFile();
            for (ClassDef classDef : dex.getClasses()) {
                for (Method method : classDef.getMethods()) {
                    if (method.getReturnType().equals(TYPEFACE)
                            && method.getImplementation() != null) {
                        factories.put(key(classDef, method), method);
                    }
                    MethodImplementation impl = method.getImplementation();
                    if (impl == null) continue;
                    int typefaceCalls = 0;
                    for (Instruction ins : impl.getInstructions()) {
                        if (!(ins instanceof ReferenceInstruction)) continue;
                        Reference ref = ((ReferenceInstruction) ins).getReference();
                        if (!(ref instanceof MethodReference)) continue;
                        MethodReference m = (MethodReference) ref;
                        String mkey = m.getDefiningClass() + "->" + m.getName()
                                + "(" + join(m.getParameterTypes()) + ")" + m.getReturnType();
                        callers.merge(mkey, 1, Integer::sum);
                        if (m.getDefiningClass().equals(TYPEFACE)) typefaceCalls++;
                    }
                    if (typefaceCalls > 0) {
                        hubs.merge(classDef.getType(), typefaceCalls, Integer::sum);
                    }
                }
            }
        }

        switch (mode) {
            case "factories": {
                List<Map.Entry<String, Method>> sorted = new ArrayList<>(factories.entrySet());
                sorted.sort((a, b) -> callers.getOrDefault(b.getKey(), 0)
                        - callers.getOrDefault(a.getKey(), 0));
                int shown = 0;
                for (Map.Entry<String, Method> e : sorted) {
                    int count = callers.getOrDefault(e.getKey(), 0);
                    if (count == 0 && shown > 40) continue;
                    Method m = e.getValue();
                    System.out.println(String.format("  %4d callers  %s%s",
                            count, e.getKey(),
                            m.getParameters().size() == 0 ? "" : ""));
                    if (++shown >= 120) {
                        System.out.println("  ... " + (sorted.size() - shown) + " more");
                        break;
                    }
                }
                System.out.println("total Typeface-returning methods with bodies: "
                        + factories.size());
                break;
            }
            case "hubs": {
                List<Map.Entry<String, Integer>> sorted = new ArrayList<>(hubs.entrySet());
                sorted.sort((a, b) -> b.getValue() - a.getValue());
                for (int i = 0; i < Math.min(60, sorted.size()); i++) {
                    System.out.println(String.format("  %5d invokes  %s",
                            sorted.get(i).getValue(), sorted.get(i).getKey()));
                }
                break;
            }
            default:
                if (mode.startsWith("class:")) {
                    String want = mode.substring("class:".length())
                            .replace('.', '/');
                    if (!want.startsWith("L")) want = "L" + want;
                    if (!want.endsWith(";")) want = want + ";";
                    for (String entryName : container.getDexEntryNames()) {
                        DexBackedDexFile dex = container.getEntry(entryName).getDexFile();
                        for (ClassDef classDef : dex.getClasses()) {
                            if (!classDef.getType().equals(want)) continue;
                            System.out.println("### " + classDef.getType()
                                    + "  super=" + classDef.getSuperclass());
                            for (Method method : classDef.getMethods()) {
                                MethodImplementation impl = method.getImplementation();
                                System.out.println("    " + method.getName()
                                        + "(" + join(method.getParameterTypes()) + ")"
                                        + method.getReturnType()
                                        + (impl == null ? "  (no body)" : ""));
                            }
                            return;
                        }
                    }
                    System.out.println("class not found: " + want);
                } else if (mode.startsWith("method:")) {
                    // method:Lclass;->name  or  method:Lclass;->name(params)ret
                    String[] parts = mode.substring("method:".length())
                            .split("->", 2);
                    String cls = parts[0];
                    String rest = parts[1];
                    int paren = rest.indexOf('(');
                    String mname = paren < 0 ? rest : rest.substring(0, paren);
                    for (String entryName : container.getDexEntryNames()) {
                        DexBackedDexFile dex = container.getEntry(entryName).getDexFile();
                        for (ClassDef classDef : dex.getClasses()) {
                            if (!classDef.getType().equals(cls)) continue;
                            for (Method method : classDef.getMethods()) {
                                if (!method.getName().equals(mname)) continue;
                                MethodImplementation impl = method.getImplementation();
                                if (impl == null) {
                                    System.out.println("no body: " + method.getName());
                                    continue;
                                }
                                System.out.println("### " + cls + "->" + mname
                                        + "  regs=" + impl.getRegisterCount());
                                int i = 0;
                                for (Instruction ins : impl.getInstructions()) {
                                    System.out.println(String.format("  %4d  %s",
                                            i++, line(ins)));
                                }
                                return;
                            }
                        }
                    }
                    System.out.println("method not found: " + mode);
                }
        }
    }

    private static String line(Instruction ins) {
        StringBuilder sb = new StringBuilder(ins.getOpcode().name);
        String registers = registersOf(ins);
        if (registers != null) sb.append(' ').append(registers);
        if (ins instanceof com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction) {
            sb.append(" #").append(((com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction) ins).getNarrowLiteral());
        }
        if (ins instanceof com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction) {
            sb.append(", ").append(((com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction) ins).getReference());
        }
        return sb.toString();
    }

    private static String registersOf(Instruction ins) {
        if (ins instanceof com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction) {
            com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction range =
                    (com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction) ins;
            int start = range.getStartRegister();
            int end = start + range.getRegisterCount() - 1;
            return "{v" + start + " .. v" + end + "}";
        }
        if (ins instanceof com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction) {
            com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction five =
                    (com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction) ins;
            int[] all = {five.getRegisterC(), five.getRegisterD(), five.getRegisterE(),
                    five.getRegisterF(), five.getRegisterG()};
            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i < five.getRegisterCount(); i++) {
                if (i > 0) sb.append(", ");
                sb.append('v').append(all[i]);
            }
            return sb.append('}').toString();
        }
        if (ins instanceof com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction) {
            com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction three =
                    (com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction) ins;
            return "{v" + three.getRegisterA() + ", v" + three.getRegisterB()
                    + ", v" + three.getRegisterC() + "}";
        }
        if (ins instanceof com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction) {
            com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction two =
                    (com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction) ins;
            return "{v" + two.getRegisterA() + ", v" + two.getRegisterB() + "}";
        }
        if (ins instanceof com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction) {
            return "v" + ((com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction) ins).getRegisterA();
        }
        return null;
    }

    private static String key(ClassDef classDef, Method method) {
        return classDef.getType() + "->" + method.getName()
                + "(" + join(method.getParameterTypes()) + ")" + method.getReturnType();
    }

    private static String join(Iterable<? extends CharSequence> parts) {
        StringBuilder sb = new StringBuilder();
        for (CharSequence part : parts) {
            if (sb.length() > 0) sb.append(',');
            sb.append(part);
        }
        return sb.toString();
    }
}
