package local.tv.home;

import java.io.IOException;
import java.util.UUID;
import org.mozilla.javascript.*;

/** Pure JS transforms only. No Java objects, filesystem, network, dynamic imports or Node globals. */
final class ProviderScript {

    static final int MAX_BYTES = 8 * 1024 * 1024;

    private ProviderScript() {}

    static String evaluate(String source, String operation, String payload) throws IOException {
        if (source.length() > 256 * 1024 || payload.length() > MAX_BYTES) throw new IOException(
            "Provider limit"
        );
        long deadline = System.nanoTime() + 3_000_000_000L;
        ContextFactory factory = new ContextFactory() {
            @Override
            protected Context makeContext() {
                Context cx = super.makeContext();
                cx.setLanguageVersion(Context.VERSION_ES6);
                cx.setOptimizationLevel(-1);
                cx.setMaximumInterpreterStackDepth(128);
                cx.setInstructionObserverThreshold(1000);
                cx.setClassShutter(name -> false);
                return cx;
            }

            @Override
            protected void observeInstructionCount(Context cx, int count) {
                if (System.nanoTime() > deadline || Thread.currentThread().isInterrupted()) {
                    // Error, not a JS-catchable exception: scripts cannot swallow the deadline.
                    throw new Deadline();
                }
            }
        };
        try {
            return factory.call(cx -> {
                ScriptableObject scope = cx.initSafeStandardObjects();
                ScriptableObject.putProperty(
                    scope,
                    "uuid",
                    new BaseFunction() {
                        @Override
                        public Object call(Context c, Scriptable s, Scriptable self, Object[] args) {
                            return UUID.randomUUID().toString();
                        }
                    }
                );
                cx.evaluateString(scope, source, "provider", 1, null);
                Object callable = ScriptableObject.getProperty(scope, "invoke");
                if (!(callable instanceof Function)) throw new IllegalArgumentException(
                    "Missing provider interface"
                );
                Object value = ((Function) callable).call(cx, scope, scope, new Object[] {
                    operation,
                    payload,
                });
                Object json = ScriptableObject.getProperty(scope, "JSON");
                Object result = ScriptableObject.callMethod(cx, (Scriptable) json, "stringify", new Object[] {
                    value,
                });
                String out = Context.toString(result);
                if (out.length() > MAX_BYTES) throw new IllegalArgumentException("Provider result too large");
                return out;
            });
        } catch (Deadline | RuntimeException e) {
            throw new IOException("Provider transform failed", e);
        }
    }

    private static final class Deadline extends Error {}
}
