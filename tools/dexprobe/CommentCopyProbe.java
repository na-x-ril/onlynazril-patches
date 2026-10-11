/**
 * Every route TikTok's comment "Copy" takes on one build.
 *
 *   tools/dexprobe/run.sh CommentCopyProbe "<apk>"
 *
 * Prints the ClipData builder (static, (String, String, List) -> ClipData, "copy_label"), the
 * plain-text clipboard helper (void, (String, String, Context, Cert), ClipData.newPlainText), the
 * methods that call that helper while reading Comment#getText and Comment#getUser (the copy menus),
 * and the callers of the builder. None of these keeps its class name between builds.
 */
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class CommentCopyProbe {
    static {
        System.loadLibrary("dexkit");
    }

    private static final String COMMENT = "com.ss.android.ugc.aweme.comment.model.Comment";
    private static final String CLIP_DATA = "android.content.ClipData";

    public static void main(String[] args) {
        try (DexKitBridge bridge = DexKitBridge.create(args[0])) {
            System.out.println("\n== ClipData builder (static (String,String,List) -> ClipData, copy_label)");
            MethodDataList builders = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingStrings("copy_label")
                            .returnType(CLIP_DATA)
                            .paramTypes("java.lang.String", "java.lang.String", "java.util.List")));
            for (MethodData m : builders) {
                describe(m);
                for (MethodData caller : m.getCallers()) {
                    System.out.println("      <- " + signature(caller)
                            + "  strings=" + caller.getUsingStrings());
                }
            }

            System.out.println("\n== plain clipboard helper (void (String,String,Context,Cert), newPlainText)");
            MethodDataList helpers = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .returnType("void")
                            .paramTypes("java.lang.String", "java.lang.String", "android.content.Context",
                                    "com.bytedance.bpea.basics.Cert")));
            for (MethodData helper : helpers) {
                boolean plain = false;
                for (MethodData invoked : helper.getInvokes()) {
                    if (invoked.getDeclaredClassName().equals(CLIP_DATA)
                            && invoked.getName().equals("newPlainText")) {
                        plain = true;
                    }
                }
                if (!plain) continue;
                System.out.println("\n  " + signature(helper));
                for (MethodData caller : helper.getCallers()) {
                    boolean text = false;
                    boolean user = false;
                    for (MethodData invoked : caller.getInvokes()) {
                        if (!invoked.getDeclaredClassName().equals(COMMENT)) continue;
                        if (invoked.getName().equals("getText")) text = true;
                        if (invoked.getName().equals("getUser")) user = true;
                    }
                    System.out.println("      <- " + signature(caller)
                            + "  getText=" + text + " getUser=" + user
                            + "  strings=" + caller.getUsingStrings());
                }
            }
        }
    }

    private static void describe(MethodData m) {
        System.out.println("\n  " + signature(m) + "  static=" + ((m.getModifiers() & 0x8) != 0));
        for (MethodData invoked : m.getInvokes()) {
            String owner = invoked.getDeclaredClassName();
            if (owner.equals(COMMENT) || owner.equals(CLIP_DATA)) {
                System.out.println("      invokes " + owner + "->" + invoked.getName()
                        + " " + invoked.getParamTypeNames() + " -> " + invoked.getReturnTypeName());
            }
        }
    }

    private static String signature(MethodData m) {
        return m.getDeclaredClassName() + "->" + m.getName() + " "
                + m.getParamTypeNames() + " -> " + m.getReturnTypeName();
    }
}
