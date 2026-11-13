package me.acardia.amalor.xposed.uxdesign

import android.app.UiModeManager
import android.content.Context
import io.github.libxposed.api.XposedModule
import me.acardia.amalor.WallpaperColorOptions
import me.acardia.amalor.xposed.systemui.NativeFeatures
import org.xmlpull.v1.XmlSerializer
import java.io.File

/** UxDesign 17.0.15: replace COUI wallpaper XML roles, leaving normal Monet/picker inputs intact. */
class WallpaperColorHook(module: XposedModule, loader: ClassLoader) {
    private val r = NativeFeatures(module, loader)
    private val bindings = WallpaperColorBindings()
    @Volatile private var context: Context? = null
    private fun enabled() = r.enabled(WallpaperColorOptions.SPEC_2025)

    fun install() {
        val converter = r.type("com.oplus.uxdesign.uxcolor.util.p")
        val copy = converter.getDeclaredMethod("a", Context::class.java, List::class.java, ArrayList::class.java)
        // These two classes are obfuscated. Validate the full 17.0.15 writer/name contract first.
        val writer = r.type("ua.f")
        val names = r.type("ua.e")
        val namesInstance = names.getDeclaredField("INSTANCE").get(null)
        val families = names.getDeclaredMethod("d").invoke(namesInstance) as Array<*>
        val suffixes = names.getDeclaredMethod("h").invoke(namesInstance) as Array<*>
        check(families.contentEquals(WallpaperColorFamily.entries.map { it.xmlName }.toTypedArray()))
        check(suffixes.contentEquals(arrayOf("TintControlNormal", "TintControlPressed", "TintLightNormal",
            "TintLightPressed", "Highlight", "BarDisabledColor", "InnerCircleDisabledColor")))
        val couiName = names.getDeclaredMethod("f", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val nxName = names.getDeclaredMethod("g", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val sharedName = names.getDeclaredMethod("c", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        val resourceNames = families.indices.map { family -> suffixes.indices.map { slot ->
            listOf(couiName.invoke(namesInstance, family, slot) as String,
                nxName.invoke(namesInstance, family, slot) as String)
        } }
        val sharedNames = (0..2).map { slot -> listOf(sharedName.invoke(namesInstance, slot, true) as String,
            sharedName.invoke(namesInstance, slot, false) as String) }
        val save = writer.getDeclaredMethod("d", ArrayList::class.java, Int::class.javaPrimitiveType)
        val body = writer.getDeclaredMethod("b", XmlSerializer::class.java, ArrayList::class.java, Boolean::class.javaPrimitiveType)
        val file = writer.getDeclaredMethod("c", File::class.java, ArrayList::class.java, Boolean::class.javaPrimitiveType)
        check(copy.returnType == Void.TYPE && body.returnType == Void.TYPE && save.returnType == Boolean::class.javaPrimitiveType)

        r.hook("wallpaper:vendor-input", copy) { chain ->
            // Normal Monet uses A1 (slot 3, moved to slot 0 by getTintColor). Capture that input
            // before HSL/Lab conversion; keep the host's original preview and return value.
            val src = chain.getArg(1) as List<*>
            val seed = (src.getOrNull(3) as? Number)?.toInt()
            val result = chain.proceed()
            if (seed != null) {
                context = (chain.getArg(0) as Context).applicationContext
                bindings.remember(chain.getArg(2)!!, seed)
            }
            result
        }
        r.hook("wallpaper:vendor-write", save) { chain ->
            val contrast = context?.getSystemService(UiModeManager::class.java)?.contrast?.toDouble() ?: 0.0
            bindings.duringWrite(chain.getArg(0)!!, chain.getArg(1) as Int, contrast, enabled()) { write ->
                chain.proceed().also { result ->
                    if (write != null) r.info("COUI wallpaper XML: seed=${hex(write.seed)}, style=${write.style}, " +
                        "primary=${hex(write.light.family(WallpaperColorFamily.PRIMARY).normal)}, " +
                        "secondary=${hex(write.light.family(WallpaperColorFamily.SECONDARY).normal)}, " +
                        "tertiary=${hex(write.light.family(WallpaperColorFamily.TERTIARY).normal)}, " +
                        "files=${write.renderedFiles}, saved=$result")
                }
            }
        }
        r.hook("wallpaper:vendor-roles", body) { chain ->
            val write = bindings.current ?: return@hook chain.proceed()
            if (write.colors !== chain.getArg(1)) return@hook chain.proceed()
            val palette = if (chain.getArg(2) as Boolean) write.dark else write.light
            val xml = chain.getArg(0) as XmlSerializer
            xml.startTag(null, "group")
            xml.startTag(null, "index").text(write.index.toString()).endTag(null, "index")
            for (family in families.indices) {
                val roleFamily = WallpaperColorFamily.entries[family]
                val roles = palette.family(roleFamily)
                xml.startTag(null, "child").attribute(null, "name", families[family] as String)
                    .attribute(null, "id", (family + 1).toString())
                for (slot in roles.values.indices) resourceNames[family][slot].forEach { xml.color(it, roles.values[slot]) }
                if (roleFamily == WallpaperColorFamily.NEUTRAL_VARIANT) {
                    val shared = intArrayOf(roles.normal, roles.disabledContainer, roles.disabledContent)
                    shared.indices.forEach { slot -> sharedNames[slot].forEach { xml.color(it, shared[slot]) } }
                    // This resource is shared across all five families; keep its foreground neutral.
                    xml.color("coui_color_label_on_color", palette.sharedOnColor)
                    xml.color("coui_color_label_on_color_light", write.light.sharedOnColor)
                    xml.color("coui_color_label_on_color_dark", write.dark.sharedOnColor)
                }
                xml.endTag(null, "child")
            }
            xml.endTag(null, "group")
            write.renderedFiles++
            null
        }
        r.module.deoptimize(file)
        r.module.deoptimize(save)
        // Both UI selection and provider/wallpaper-update flows call the same converter and writer.
        r.type("pa.i").declaredMethods.forEach { r.module.deoptimize(it) }
        r.type("com.oplus.uxdesign.uxcolor.UxColorSettingProvider").declaredMethods.forEach { r.module.deoptimize(it) }
    }

    private fun hex(color: Int) = "#" + color.toUInt().toString(16).padStart(8, '0')
    private fun XmlSerializer.color(name: String, color: Int) {
        startTag(null, "color").attribute(null, "name", name).text(hex(color)).endTag(null, "color")
    }
}
