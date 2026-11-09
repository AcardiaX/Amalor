package me.acardia.amalor.xposed.systemui

import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import me.acardia.amalor.NativeFeatureOptions

/** Disable only the QS color spotlight; the OEM touch/scale feedback remains in place. */
internal class QsSpotLightHook(private val r: NativeFeatures, private val isTile: (View) -> Boolean) {
    fun install() {
        val shared = r.type("com.oplus.systemui.qs.base.spotlight.SharedSpotLightEffect")
        val detach = shared.getDeclaredMethod("detach", View::class.java, String::class.java)
        for ((name, argument) in listOf("onTouchEvent" to MotionEvent::class.java, "draw" to Canvas::class.java)) {
            val method = shared.getDeclaredMethod(name, View::class.java, argument)
            r.hook("qs-appearance:spotlight-disabled:$name", method) { chain ->
                val view = chain.getArg(0) as View
                if (r.enabled(NativeFeatureOptions.QS_TILE_NATIVE_APPEARANCE) && isTile(view)) {
                    if (name == "onTouchEvent" && (chain.getArg(1) as MotionEvent).actionMasked == MotionEvent.ACTION_DOWN) {
                        detach.invoke(null, view, "Amalor:systemRoleTiles")
                    }
                    null
                } else chain.proceed()
            }
            r.module.deoptimize(method)
        }
    }
}
