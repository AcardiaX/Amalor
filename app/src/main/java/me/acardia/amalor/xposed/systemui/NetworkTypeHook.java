package me.acardia.amalor.xposed.systemui;

import android.util.Log;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import me.acardia.amalor.NetworkTypeOptions;
import me.acardia.amalor.xposed.HookLog;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** ColorOS 17.99.02: native network-label layout/fonts for independent and stacked signals. */
public final class NetworkTypeHook {
    private final XposedModule module;
    private static final String TAG = "Amalor";
    private static final String COMPOSABLE =
            "com.android.systemui.statusbar.pipeline.shared.ui.composable.";
    static final String VIEW_BINDING =
            "com.oplus.systemui.statusbar.pipeline.mobile.ui.view.OplusStatusBarMobileViewBinder$Os17Binding";

    // Compose uses three changed bits and one default bit per source parameter.
    private static final int FONT_CHANGED_MASK = (7 << 13) | (7 << 16) | (7 << 19);
    private static final int FONT_DEFAULT_MASK = (1 << 4) | (1 << 5) | (1 << 6);

    private boolean strategyInstalled;
    private boolean nativeLayoutInstalled;
    private boolean fontInstalled;
    private boolean viewFontInstalled;
    private boolean artworkInstalled;
    private boolean viewArtworkInstalled;
    private boolean viewFontReported;
    private boolean optionsLoaded;
    private boolean active;
    private boolean useBold;
    private int fontStyle;
    private Typeface googleRounded;

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
                HookLog.write(module, Log.ERROR, TAG, "could not read network type settings; keeping system defaults", error);
                return;
            }
        }
        if (!active) return;
        // Track each hook separately so a later callback only retries the failed part.
        if (!nativeLayoutInstalled) {
            try {
                installNativeLayout(loader);
                nativeLayoutInstalled = true;
                HookLog.write(module, Log.INFO, TAG, "installed native independent network-type layout feature");
            } catch (Throwable error) {
                HookLog.write(module, Log.ERROR, TAG, "native network-type layout feature hook failed", error);
            }
        }
        if (!strategyInstalled) {
            try {
                installStackedStrategy(loader);
                strategyInstalled = true;
                HookLog.write(module, Log.INFO, TAG, "installed merged dual-SIM BigType strategy");
            } catch (Throwable error) {
                HookLog.write(module, Log.ERROR, TAG, "merged dual-SIM strategy hook failed", error);
            }
        }
        if (!artworkInstalled) {
            try {
                PixelNetworkArtworkHook.install(module, loader);
                artworkInstalled = true;
            } catch (Throwable error) {
                HookLog.write(module, Log.ERROR, TAG, "Pixel network artwork hook failed", error);
            }
        }
        if (!viewArtworkInstalled) {
            try {
                PixelNetworkArtworkHook.installView(module, loader);
                viewArtworkInstalled = true;
                HookLog.write(module, Log.INFO, TAG, "installed independent network artwork binding");
            } catch (Throwable error) {
                HookLog.write(module, Log.ERROR, TAG, "independent network artwork hook failed", error);
            }
        }
        if (!viewFontInstalled && (fontStyle == NetworkTypeOptions.SYSTEM || fontStyle == NetworkTypeOptions.GOOGLE_ROUNDED)) {
            try {
                installViewNetworkFont(loader);
                viewFontInstalled = true;
                HookLog.write(module, Log.INFO, TAG, "installed independent network font binding");
            } catch (Throwable error) {
                HookLog.write(module, Log.ERROR, TAG, "independent network font hook failed", error);
            }
        }
        if (!fontInstalled && (fontStyle == NetworkTypeOptions.SYSTEM || fontStyle == NetworkTypeOptions.GOOGLE_ROUNDED)) {
            try {
                installStackedNetworkFont(loader);
                fontInstalled = true;
                HookLog.write(module, Log.INFO, TAG, "installed merged dual-SIM network font");
            } catch (Throwable error) {
                HookLog.write(module, Log.ERROR, TAG, "merged dual-SIM font hook failed", error);
            }
        }
    }

    private void installNativeLayout(ClassLoader loader) throws ReflectiveOperationException {
        String provider = "com.oplus.coreapp.appfeature.AppFeatureProviderUtils";
        Method query = loader.loadClass(provider).getDeclaredMethod("isFeatureSupport",
                android.content.ContentResolver.class, loader.loadClass(provider + "$FeatureID"), String.class);
        if (query.getReturnType() != boolean.class) throw new NoSuchMethodException(query.toString());
        // Like ONextBox, enable the native feature before CustomizeFeatureOption caches it.
        // The factory still chooses the correct OS16/OS17 binder and SIM layout itself.
        module.hook(query).setId("amalor-native-big-network-type")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> "com.android.systemui.statusbar_show_big_5g".equals(chain.getArg(2))
                        ? true : chain.proceed());
    }

    private void installViewNetworkFont(ClassLoader loader) throws ReflectiveOperationException, IOException {
        Class<?> binding = loader.loadClass(VIEW_BINDING);
        Field text = binding.getDeclaredField("mobileTypeText");
        Field helper = binding.getDeclaredField("systemTypefaceHelper");
        text.setAccessible(true);
        helper.setAccessible(true);
        Method defaultFont = helper.getType().getDeclaredMethod("getDefaultTypeface", int.class);
        Typeface rounded = fontStyle == NetworkTypeOptions.GOOGLE_ROUNDED ? loadGoogleRounded() : null;
        int weight = useBold ? 700 : 400;
        Method assign = binding.getDeclaredMethod("access$setMobileTypeTextTypeface", binding,
                Typeface.class, int.class, boolean.class);
        Method initialize = binding.getDeclaredMethod("initViews", int.class);
        // Initial binding and later font/network Flow emissions both assign the TextView face.
        // 3G/4G are not isLarge(), so the width-25 font helper alone would miss them.
        for (Method method : new Method[]{initialize, assign}) {
            module.hook(method).setId("amalor-independent-network-font:" + method.getName())
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object target = method == assign ? chain.getArg(0) : chain.getThisObject();
                        Typeface selected = rounded;
                        if (selected == null) {
                            Typeface system = (Typeface) defaultFont.invoke(helper.get(target), weight);
                            selected = Typeface.create(system, weight, system.isItalic());
                        }
                        TextView view = (TextView) text.get(target);
                        view.setTypeface(selected);
                        int padding = independentLabelPadding(view);
                        // Set one inset per slot; repeated font/label emissions must not add it again.
                        if (view.getPaddingStart() != padding || view.getPaddingEnd() != padding) {
                            view.setPaddingRelative(padding, view.getPaddingTop(), padding, view.getPaddingBottom());
                        }
                        if (!viewFontReported) {
                            viewFontReported = true;
                            HookLog.write(module, Log.INFO, TAG, "independent network font applied: " + fontStyle);
                        }
                        return result;
                    });
        }
        deoptimizeViewBinding(module, loader);
    }

    static int independentLabelPadding(TextView view) {
        return Math.max(0, Math.round(2f * view.getResources().getDisplayMetrics().density));
    }

    static void deoptimizeViewBinding(XposedModule module, ClassLoader loader) throws ReflectiveOperationException {
        // R8 merges the font and network-text Flow collectors into this one emit method.
        for (Method method : loader.loadClass(VIEW_BINDING + "$bindSubclassFlows$1$1").getDeclaredMethods()) {
            if (method.getName().equals("emit")) module.deoptimize(method);
        }
        module.deoptimize(loader.loadClass(VIEW_BINDING).getDeclaredMethod("initViews", int.class));
        for (Method method : loader.loadClass(
                "com.oplus.systemui.statusbar.pipeline.mobile.ui.view.AbstractOplusStatusBarMobileViewBinder").getDeclaredMethods()) {
            if (method.getName().equals("bindCustEx")) module.deoptimize(method);
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
        if (googleRounded != null) return googleRounded;
        // Read the module APK directly: no target Context, hidden AssetManager API or temp file.
        try (ZipFile apk = new ZipFile(module.getModuleApplicationInfo().sourceDir)) {
            ZipEntry entry = apk.getEntry("assets/fonts/GoogleSansRounded-Bold.ttf");
            if (entry == null) throw new IOException("Missing bundled Google Sans Rounded font");
            try (InputStream input = apk.getInputStream(entry)) {
                byte[] bytes = input.readAllBytes();
                ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
                buffer.put(bytes).flip();
                Font font = new Font.Builder(buffer).build();
                googleRounded = new Typeface.CustomFallbackBuilder(new FontFamily.Builder(font).build())
                        .setStyle(font.getStyle()).setSystemFallback("sans-serif").build();
                return googleRounded;
            }
        }
    }

    private void deoptimizeNamedMethods(ClassLoader loader, String className, String methodName)
            throws ReflectiveOperationException {
        boolean found = false;
        for (Method method : loader.loadClass(COMPOSABLE + className).getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                if (!module.deoptimize(method)) {
                    HookLog.write(module, Log.WARN, TAG, "could not deoptimize " + className + "." + methodName);
                }
                found = true;
            }
        }
        if (!found) throw new NoSuchMethodException(className + "." + methodName);
    }
}
