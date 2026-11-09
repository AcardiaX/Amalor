package me.acardia.amalor.xposed.wetype

import android.content.res.AssetManager
import android.graphics.Typeface
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import me.acardia.amalor.NetworkTypeOptions
import me.acardia.amalor.WeTypeOptions
import me.acardia.amalor.xposed.CachedBooleanPreferences

/** WeType 4.0.1 self-drawn keyboard Paints load this asset before calling setTypeface. */
class WeTypeFontHook(private val module: XposedModule) {
    private val flags = CachedBooleanPreferences(module.getRemotePreferences(NetworkTypeOptions.GROUP))

    fun install() {
        val create = Typeface::class.java.getDeclaredMethod(
            "createFromAsset", AssetManager::class.java, String::class.java)
        module.hook(create).setId("amalor-wetype:system-font")
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                if (chain.getArg(1) == "fonts/WE-Regular.ttf" && flags.get(WeTypeOptions.FOLLOW_SYSTEM_FONT))
                    Typeface.DEFAULT
                else chain.proceed()
            }
    }
}
