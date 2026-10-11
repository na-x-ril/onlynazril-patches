/**
 * The entry points that show TikTok's risk-control (CAPTCHA) puzzle, as this build carries them.
 *
 *   tools/dexprobe/run.sh CaptchaProbe "<apk>"
 *
 * SecApiImpl, LiveHostUser, BdTuring and oecverify are real-named classes, so they are found by
 * name; the puzzle is shown from a handful of methods with stable names, and the callback that is
 * told the puzzle was dismissed is what each signature ends with.
 */
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.MethodData;

public class CaptchaProbe {
    static {
        System.loadLibrary("dexkit");
    }

    private static final String[] NEEDLES = {
            "SecApiImpl", "oecverify", "BdTuring", "LiveHostUser", "RiskControlService",
    };

    public static void main(String[] args) {
        try (DexKitBridge bridge = DexKitBridge.create(args[0])) {
            for (String needle : NEEDLES) {
                ClassDataList classes = bridge.findClass(FindClass.create()
                        .matcher(ClassMatcher.create()
                                .className(needle, StringMatchType.Contains)));
                if (classes.isEmpty()) continue;
                System.out.println("\n== classes matching \"" + needle + "\" : " + classes.size());
                for (ClassData c : classes) {
                    System.out.println("  " + c.getName() + "  methods=" + c.getMethodCount());
                    for (MethodData m : c.getMethods()) {
                        String n = m.getName();
                        if (!(n.startsWith("popCaptcha") || n.equals("execute")
                                || n.equals("showVerifyDialog") || n.equals("showCaptcha"))) {
                            continue;
                        }
                        System.out.println("      " + n + " " + m.getParamTypeNames()
                                + " -> " + m.getReturnTypeName()
                                + "  static=" + ((m.getModifiers() & 0x8) != 0)
                                + "  strings=" + m.getUsingStrings());
                    }
                }
            }
        }
    }
}
