package me.acardia.amalor.xposed.systemui;

import android.util.Log;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import me.acardia.amalor.NetworkTypeOptions;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** ColorOS 17.99.02: native BigType layout and system font for merged dual-SIM icons. */
public final class NetworkTypeHook {
    private final XposedModule module;
    private static final String TAG = "Amalor";
    private static final String COMPOSABLE =
            "com.android.systemui.statusbar.pipeline.shared.ui.composable.";

    // Compose uses three changed bits and one default bit per source parameter.
    private static final int FONT_CHANGED_MASK = (7 << 13) | (7 << 16) | (7 << 19);
    private static final int FONT_DEFAULT_MASK = (1 << 4) | (1 << 5) | (1 << 6);

    private boolean strategyInstalled;
    private boolean fontInstalled;
    private boolean optionsLoaded;
    private boolean active;
    private boolean useBold;
    private int fontStyle;

    public NetworkTypeHook(XposedModule module) {
        this.module = module;
    }

    public synchronized void install(ClassLoader loader) {
        if (loader == null) return;
        if (!optionsLoaded) {
            try {
                SharedPreferences preferences = module.getRemotePreferences(NetworkTypeOptions.GROUP);
                int mode = preferences.getInt(NetworkTypeOptions.MODE, 0);
                fontStyle = NetworkTypeOptions.supportedFont(
                        preferences.getInt(NetworkTypeOptions.FONT, NetworkTypeOptions.OFFICIAL));
                // Enabling the modification always implies separate display.
                active = NetworkTypeOptions.isActive(mode);
                useBold = NetworkTypeOptions.canUseBold(mode, fontStyle)
                        && preferences.getBoolean(NetworkTypeOptions.BOLD, false);
                optionsLoaded = true; // Settings are applied on the next SystemUI restart.
            } catch (Throwable error) {
                module.log(Log.ERROR, TAG, "could not read network type settings; keeping system defaults", error);
                return;
            }
        }
        if (!active) return;
        // Track each hook separately so a later callback only retries the failed part.
        if (!strategyInstalled) {
            try {
                installStackedStrategy(loader);
                strategyInstalled = true;
                module.log(Log.INFO, TAG, "installed merged dual-SIM BigType strategy");
            } catch (Throwable error) {
                module.log(Log.ERROR, TAG, "merged dual-SIM strategy hook failed", error);
            }
        }
        if (!fontInstalled && fontStyle != NetworkTypeOptions.OFFICIAL) {
            try {
                installStackedNetworkFont(loader);
                fontInstalled = true;
                module.log(Log.INFO, TAG, "installed merged dual-SIM network font");
            } catch (Throwable error) {
                module.log(Log.ERROR, TAG, "merged dual-SIM font hook failed", error);
            }
        }
    }

    private void installStackedStrategy(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> factory = loader.loadClass(COMPOSABLE + "OplusStackedMobileIconLayoutStrategyFactory");
        Object bigStrategy = loader.loadClass(COMPOSABLE + "BigTypeStackedMobileIconLayoutStrategy")
                .getField("INSTANCE").get(null);
        // Deoptimize the caller, where ART may have inlined the factory getter.
        deoptimizeNamedMethods(loader, "OplusStackedMobileIconKt", "OplusStackedMobileIcon");
        module.hook(factory.getDeclaredMethod("getStrategy"))
                .setId("amalor-big-network-type-strategy")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> bigStrategy);
    }

    private void installStackedNetworkFont(ClassLoader loader) throws ReflectiveOperationException, IOException {
        Class<?> fontFamily = loader.loadClass("androidx.compose.ui.text.font.FontFamily");
        Object selectedFont;
        if (fontStyle == NetworkTypeOptions.GOOGLE_ROUNDED) {
            selectedFont = loader.loadClass("androidx.compose.ui.text.font.AndroidTypeface_androidKt")
                    .getMethod("FontFamily", Typeface.class).invoke(null, loadGoogleRounded());
        } else {
            Object familyCompanion = fontFamily.getField("Companion").get(null);
            selectedFont = familyCompanion.getClass().getMethod("getSansSerif").invoke(familyCompanion);
        }
        Class<?> fontWeight = loader.loadClass("androidx.compose.ui.text.font.FontWeight");
        Object weightCompanion = fontWeight.getField("Companion").get(null);
        Object selectedWeight = weightCompanion.getClass()
                .getMethod(useBold || fontStyle == NetworkTypeOptions.GOOGLE_ROUNDED ? "getBold" : "getNormal")
                .invoke(weightCompanion);

        // Match the complete APK signature before replacing positional arguments.
        Method networkText = loader.loadClass(COMPOSABLE + "OplusStackedMobileIconLayoutStrategyKt")
                .getDeclaredMethod("NetworkTypeText-Y85pibM",
                        loader.loadClass("com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.StackedMobileIconViewModel"),
                        loader.loadClass("com.android.systemui.statusbar.pipeline.mobile.domain.model.OplusNetworkTypeTextModel"),
                        long.class, loader.loadClass("androidx.compose.ui.Modifier"),
                        fontFamily, fontWeight, fontFamily,
                        loader.loadClass(COMPOSABLE + "OplusStackedMobileIconDimensions"),
                        loader.loadClass("androidx.compose.ui.unit.TextUnit"),
                        loader.loadClass("androidx.compose.ui.unit.TextUnit"),
                        loader.loadClass("androidx.compose.runtime.Composer"), int.class, int.class);

        deoptimizeNamedMethods(loader, "BigTypeStackedMobileIconLayoutStrategy", "StackedMobileIcon");
        // The default strategy also calls NetworkTypeText if the strategy hook is unavailable.
        deoptimizeNamedMethods(loader, "DefaultStackedMobileIconLayoutStrategy", "StackedMobileIcon");
        deoptimizeNamedMethods(loader,
                "OplusStackedMobileIconLayoutStrategyKt$$ExternalSyntheticLambda0", "invoke");
        module.hook(networkText)
                .setId("amalor-system-network-font")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object[] args = chain.getArgs().toArray(new Object[0]);
                    args[4] = selectedFont;
                    args[5] = selectedWeight;
                    args[6] = selectedFont; // NetworkTypeText.isLarge() selects this family.
                    // Recalculate font changes; prevent defaults from replacing our values.
                    args[11] = (Integer) args[11] & ~FONT_CHANGED_MASK;
                    args[12] = (Integer) args[12] & ~FONT_DEFAULT_MASK;
                    // Keep the native model, including null: visibility is owned by its Flow.
                    return chain.proceed(args);
                });
    }

    private Typeface loadGoogleRounded() throws IOException {
        // Read the module APK directly: no target Context, hidden AssetManager API or temp file.
        try (ZipFile apk = new ZipFile(module.getModuleApplicationInfo().sourceDir)) {
            ZipEntry entry = apk.getEntry("assets/fonts/GoogleSansRounded-Bold.ttf");
            if (entry == null) throw new IOException("Missing bundled Google Sans Rounded font");
            try (InputStream input = apk.getInputStream(entry)) {
                byte[] bytes = input.readAllBytes();
                ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
                buffer.put(bytes).flip();
                Font font = new Font.Builder(buffer).build();
                return new Typeface.CustomFallbackBuilder(new FontFamily.Builder(font).build())
                        .setStyle(font.getStyle()).setSystemFallback("sans-serif").build();
            }
        }
    }

    private void deoptimizeNamedMethods(ClassLoader loader, String className, String methodName)
            throws ReflectiveOperationException {
        boolean found = false;
        for (Method method : loader.loadClass(COMPOSABLE + className).getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                if (!module.deoptimize(method)) {
                    module.log(Log.WARN, TAG, "could not deoptimize " + className + "." + methodName);
                }
                found = true;
            }
        }
        if (!found) throw new NoSuchMethodException(className + "." + methodName);
    }
}
