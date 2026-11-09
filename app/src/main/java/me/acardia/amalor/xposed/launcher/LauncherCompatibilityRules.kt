package me.acardia.amalor.xposed.launcher

import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.function.Consumer
import java.util.function.Predicate

/** Contracts verified against both Launcher 17.3.12 and 17.5.4. */
internal object LauncherCompatibilityRules {
    fun initialPage(selected: Int): Int? = selected.takeIf { it in 1..10 }

    fun landingPage(selected: Int, pageCount: Int): Int? =
        initialPage(selected)?.takeIf { pageCount > 0 }?.coerceAtMost(pageCount - 1)

    // WorkspaceItemInfo.isPromise was inlined in 17.5.4. Keep the OEM high bit too.
    fun isPromise(status: Int): Boolean = (status and 0x40000003) != 0

    @Throws(NoSuchMethodException::class)
    fun widgetBinding(type: Class<*>, item: Class<*>, bitmap: Class<*>): Method =
        listOf(arrayOf(item, Consumer::class.java),
            arrayOf(item, Float::class.javaPrimitiveType!!, Consumer::class.java, bitmap))
            .firstNotNullOfOrNull { params ->
                runCatching { type.getMethod("applyFromCellItem", *params) }.getOrNull()
                    ?.takeIf { it.returnType == Void.TYPE && !Modifier.isAbstract(it.modifiers) }
            } ?: throw NoSuchMethodException("${type.name}.applyFromCellItem: no supported widget binding")

    fun widgetArguments(method: Method, item: Any?, preview: Consumer<*>): Array<Any?> =
        if (method.parameterCount == 2) arrayOf(item, preview) else arrayOf(item, 1f, preview, null)

    @Throws(NoSuchMethodException::class)
    fun stateCheck(launcher: Class<*>, state: Class<*>): Method = launcher.methods.filter {
        it.name == "isInState" && it.returnType == Boolean::class.javaPrimitiveType &&
            it.parameterCount == 1 && it.parameterTypes[0].isAssignableFrom(state)
    }.minByOrNull { if (it.parameterTypes[0] == state) 0 else 1 }
        ?: throw NoSuchMethodException("${launcher.name}.isInState")

    /** Resolve metadata now; reading NORMAL must wait for a real, initialized launcher callback. */
    @Throws(ReflectiveOperationException::class)
    fun normalStateCheck(launcher: Class<*>, state: Class<*>): Predicate<Any> {
        val normal = state.getDeclaredField("NORMAL").apply { isAccessible = true }
        require(Modifier.isStatic(normal.modifiers) && state.isAssignableFrom(normal.type))
        val check = stateCheck(launcher, state).apply { isAccessible = true }
        return Predicate { owner -> check.invoke(owner, normal.get(null)) == true }
    }

    @Throws(NoSuchFieldException::class)
    fun backController(manager: Class<*>): Class<*> = manager.getDeclaredField("mBackAnimationController").type

    fun backBegin(controller: Class<*>, motion: Class<*>): Method = controller.declaredFields.asSequence()
        .map { it.type }.distinct().flatMap { it.declaredMethods.asSequence() }.single { method ->
            val params = method.parameterTypes
            method.returnType == Void.TYPE && !Modifier.isAbstract(method.modifiers) && !Modifier.isStatic(method.modifiers) &&
                params.size == 2 && params[0] == motion && params[1].declaredFields.any {
                    it.type == controller && !Modifier.isStatic(it.modifiers)
                }
        }.apply { isAccessible = true }

    fun backUpdate(callback: Class<*>, event: Class<*>): Method? = callback.declaredMethods.singleOrNull {
        it.returnType == Void.TYPE && !Modifier.isAbstract(it.modifiers) && !Modifier.isStatic(it.modifiers) &&
            it.parameterTypes.contentEquals(arrayOf(event))
    }?.apply { isAccessible = true }

    fun backDispatch(animator: Class<*>): Method = animator.declaredMethods.single {
        it.returnType == Void.TYPE && !Modifier.isAbstract(it.modifiers) && !Modifier.isStatic(it.modifiers) &&
            it.parameterTypes.contentEquals(arrayOf(Float::class.javaPrimitiveType))
    }.apply { isAccessible = true }

    fun backProgress(animatedValue: Float): Float = animatedValue / 100f

    data class BackLayout(val bounds: String, val touch: String, val current: String, val crop: String,
        val session: String, val initialRadius: String, val currentRadius: String,
        val launcher: String, val endRadius: String, val finish: String)

    private val backLayouts = listOf(
        BackLayout("c", "z", "s", "o", "q", "y", "d0", "w", "v", "s"),
        BackLayout("r", "F", "y", "u", "w", "E", "j0", "C", "k", "h"),
    )

    @Throws(NoSuchFieldException::class)
    fun backLayout(type: Class<*>, rect: Class<*>, rectF: Class<*>, point: Class<*>): BackLayout =
        backLayouts.singleOrNull { layout -> runCatching {
            fun field(name: String) = type.getDeclaredField(name).type
            field(layout.bounds) == rect && field(layout.crop) == rect && field(layout.current) == rectF &&
                field(layout.touch) == point && WeakReference::class.java.isAssignableFrom(field(layout.launcher)) &&
                field(layout.initialRadius) == Float::class.javaPrimitiveType && field(layout.currentRadius) == Float::class.javaPrimitiveType &&
                field(layout.session).getDeclaredField("d").type == Float::class.javaPrimitiveType &&
                type.getDeclaredMethod(layout.endRadius).returnType == Float::class.javaPrimitiveType &&
                type.getDeclaredMethod(layout.finish).returnType == Void.TYPE
        }.getOrDefault(false) } ?: throw NoSuchFieldException("Unsupported launcher back-controller layout: ${type.name}")
}
