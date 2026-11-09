package me.acardia.amalor.xposed.systemui;

import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.Bitmap;
import android.util.LruCache;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import me.acardia.amalor.NetworkTypeOptions;
import me.acardia.amalor.R;

/** Uses the ONextBox native-style network artwork for the existing ColorOS label slot. */
final class PixelNetworkArtworkHook {
    private static android.content.Context artworkContext;
    private static final LruCache<Long, Object> artworkImages = new LruCache<>(64);
    private static final ClassValue<Method[]> modelAccessors = new ClassValue<>() {
        @Override protected Method[] computeValue(Class<?> type) {
            try { return new Method[]{type.getMethod("getNetworkTypeText")}; }
            catch (ReflectiveOperationException ignored) { return new Method[0]; }
        }
    };
    private static final ClassValue<Method[]> textAccessors = new ClassValue<>() {
        @Override protected Method[] computeValue(Class<?> type) {
            try { return new Method[]{type.getMethod("getName"), type.getMethod("getPrefix"), type.getMethod("getSuffix")}; }
            catch (ReflectiveOperationException ignored) { return new Method[0]; }
        }
    };
    private static final AtomicInteger selectedFont = new AtomicInteger(NetworkTypeOptions.OFFICIAL);
    private static final android.content.SharedPreferences.OnSharedPreferenceChangeListener fontListener = (prefs, key) -> {
        if (key == null || NetworkTypeOptions.FONT.equals(key)) {
            selectedFont.set(prefs.getInt(NetworkTypeOptions.FONT, NetworkTypeOptions.OFFICIAL));
        }
    };
    private static final Map<String, Integer> ICONS = new HashMap<>();
    static {
        ICONS.put("G", R.drawable.network_g);
        ICONS.put("E", R.drawable.network_e);
        ICONS.put("H", R.drawable.network_h);
        ICONS.put("H+", R.drawable.network_h_plus);
        ICONS.put("1X", R.drawable.network_1x);
        ICONS.put("2G", R.drawable.network_2g);
        ICONS.put("2G+", R.drawable.network_2g_plus);
        ICONS.put("3G", R.drawable.network_3g);
        ICONS.put("3G+", R.drawable.network_3g_plus);
        ICONS.put("4G", R.drawable.network_4g);
        ICONS.put("4G+", R.drawable.network_4g_plus);
        ICONS.put("4.5G", R.drawable.network_4_5g);
        ICONS.put("4.5G+", R.drawable.network_4_5g_plus);
        ICONS.put("5G", R.drawable.network_5g);
        ICONS.put("5G+", R.drawable.network_5g_plus);
        ICONS.put("5GA", R.drawable.network_5g_a);
        ICONS.put("5G-A", R.drawable.network_5g_a);
        ICONS.put("5GE", R.drawable.network_5g_e);
        ICONS.put("5.5G", R.drawable.network_5_5g);
        ICONS.put("5G++", R.drawable.network_5g_plus_plus);
        ICONS.put("5GUC", R.drawable.network_5g_uc);
        ICONS.put("5GUW", R.drawable.network_5g_uw);
        ICONS.put("5GUWB", R.drawable.network_5g_uw);
        ICONS.put("TIGO_5G_CONNECTED", R.drawable.network_tigo_connected);
        ICONS.put("TIGO_5G_NOT_RESTRICTED", R.drawable.network_tigo_available);
    }

    private PixelNetworkArtworkHook() {}

    static void install(XposedModule module, ClassLoader loader) throws Exception {
        android.content.SharedPreferences preferences = module.getRemotePreferences(NetworkTypeOptions.GROUP);
        selectedFont.set(preferences.getInt(NetworkTypeOptions.FONT, NetworkTypeOptions.OFFICIAL));
        preferences.registerOnSharedPreferenceChangeListener(fontListener);
        hookCompose(module, loader);
    }

    private static void hookCompose(XposedModule module, ClassLoader loader) throws Exception {
        String ownerName = "com.android.systemui.statusbar.pipeline.shared.ui.composable.OplusStackedMobileIconLayoutStrategyKt";
        Class<?> owner = loader.loadClass(ownerName);
        Method networkText = java.util.Arrays.stream(owner.getDeclaredMethods())
                .filter(method -> method.getName().startsWith("NetworkTypeText-") && method.getParameterCount() == 13)
                .findFirst().orElseThrow(NoSuchMethodException::new);
        Class<?> imageBitmap = loader.loadClass("androidx.compose.ui.graphics.ImageBitmap");
        Method icon = java.util.Arrays.stream(loader.loadClass("androidx.compose.material3.IconKt").getDeclaredMethods())
                .filter(method -> method.getName().startsWith("Icon-") && method.getParameterTypes()[0] == imageBitmap)
                .findFirst().orElseThrow(NoSuchMethodException::new);
        Class<?> composerType = loader.loadClass("androidx.compose.runtime.Composer");
        Method startGroup = composerType.getMethod("startRestartGroup", int.class);
        Method endGroup = composerType.getMethod("endRestartGroup");
        Class<?> function2 = loader.loadClass("kotlin.jvm.functions.Function2");
        Method updateScope = loader.loadClass("androidx.compose.runtime.ScopeUpdateScope")
                .getMethod("updateScope", function2);
        Object unit = loader.loadClass("kotlin.Unit").getField("INSTANCE").get(null);
        java.lang.reflect.Constructor<?> imageBitmapConstructor = loader.loadClass("androidx.compose.ui.graphics.AndroidImageBitmap")
                .getConstructor(Bitmap.class);
        for (String caller : new String[]{"BigTypeStackedMobileIconLayoutStrategy",
                "DefaultStackedMobileIconLayoutStrategy", "OplusStackedMobileIconLayoutStrategyKt$$ExternalSyntheticLambda0"}) {
            for (Method method : loader.loadClass("com.android.systemui.statusbar.pipeline.shared.ui.composable." + caller).getDeclaredMethods()) {
                if (method.getName().equals("StackedMobileIcon") || method.getName().equals("invoke")) module.deoptimize(method);
            }
        }
        Object defaultModifier = loader.loadClass("androidx.compose.ui.Modifier").getField("Companion").get(null);
        module.deoptimize(networkText);
        module.hook(networkText).setId("amalor-network-pixel-artwork-compose")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    if (selectedFont.get() != NetworkTypeOptions.PIXEL) {
                        return chain.proceed();
                    }
                    Object[] args = chain.getArgs().toArray(new Object[0]);
                    Object model = args[1];
                    String label = modelLabel(model);
                    Integer id = label == null ? null : ICONS.get(label.toUpperCase(Locale.ROOT).replace(" ", ""));
                    if (id == null) return chain.proceed();
                    if (artworkContext == null) {
                        android.content.Context systemContext = (android.content.Context) Class.forName("android.app.ActivityThread")
                                .getMethod("currentApplication").invoke(null);
                        artworkContext = systemContext.createPackageContext(
                                module.getModuleApplicationInfo().packageName, android.content.Context.CONTEXT_IGNORE_SECURITY);
                    }
                    // The vector is monochrome; Compose applies the live tint separately. Reuse
                    // rasterization/ImageBitmap identity instead of scanning pixels on recomposition.
                    long cacheKey = ((long) artworkContext.getResources().getDisplayMetrics().densityDpi << 32)
                            | (id & 0xffffffffL);
                    Object image = artworkImages.get(cacheKey);
                    if (image == null) {
                        Drawable drawable = artworkContext.getDrawable(id);
                        if (drawable == null) return chain.proceed();
                        image = imageBitmapConstructor.newInstance(rasterizeCentered(drawable));
                        artworkImages.put(cacheKey, image);
                    }
                    Object composer = args[10];
                    Object group = startGroup.invoke(composer, 0x642c5f5f);
                    Object modifier = (((Integer) args[12]) & 8) != 0 || args[3] == null ? defaultModifier : args[3];
                    icon.invoke(null, image, label, modifier, args[2], group, 0, 0);
                    Object scope = endGroup.invoke(group);
                    if (scope != null) {
                        Object target = chain.getThisObject();
                        Object restart = Proxy.newProxyInstance(function2.getClassLoader(), new Class[]{function2},
                                (proxy, method, values) -> {
                                    if (method.getName().equals("invoke")) {
                                        Object[] replay = args.clone();
                                        replay[10] = values[0];
                                        replay[11] = ((Integer) replay[11]) | 1;
                                        try { networkText.invoke(target, replay); }
                                        catch (InvocationTargetException error) { throw error.getTargetException(); }
                                        return unit;
                                    }
                                    if (method.getName().equals("equals")) return proxy == values[0];
                                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                                    return "PixelNetworkArtworkRestart";
                                });
                        updateScope.invoke(scope, restart);
                    }
                    return null;
                });
    }

    /** Rasterizes the vector at its native size and centers only its visible pixels vertically. */
    private static Bitmap rasterizeCentered(Drawable drawable) {
        int width = Math.max(1, drawable.getIntrinsicWidth());
        int height = Math.max(1, drawable.getIntrinsicHeight());
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        drawable.setBounds(0, 0, width, height);
        drawable.draw(new Canvas(bitmap));

        int top = height;
        int bottom = -1;
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1);
            for (int pixel : row) {
                if ((pixel >>> 24) != 0) {
                    top = Math.min(top, y);
                    bottom = y;
                    break;
                }
            }
        }
        if (bottom < top) return bitmap;
        float visibleCenter = (top + bottom) / 2f;
        float canvasCenter = (height - 1) / 2f;
        float shift = canvasCenter - visibleCenter;
        if (Math.abs(shift) < 0.01f) return bitmap;

        Bitmap centered = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(centered);
        canvas.translate(0f, shift);
        canvas.drawBitmap(bitmap, 0f, 0f, null);
        bitmap.recycle();
        return centered;
    }

    private static String modelLabel(Object model) {
        if (model == null || !model.getClass().getName().endsWith("OplusNetworkTypeTextModel$DefaultNetworkTypeText")) return null;
        try {
            Method[] modelMethods = modelAccessors.get(model.getClass());
            if (modelMethods.length == 0) return null;
            Object text = modelMethods[0].invoke(model);
            if (text == null) return null;
            Method[] methods = textAccessors.get(text.getClass());
            if (methods.length == 0) return null;
            String name = (String) methods[0].invoke(text);
            if ("TIGO_5G_CONNECTED".equals(name) || "TIGO_5G_NOT_RESTRICTED".equals(name)) return name;
            String prefix = (String) methods[1].invoke(text);
            String suffix = (String) methods[2].invoke(text);
            return prefix == null || suffix == null ? null : prefix + suffix;
        } catch (Throwable ignored) { return null; }
    }

}
