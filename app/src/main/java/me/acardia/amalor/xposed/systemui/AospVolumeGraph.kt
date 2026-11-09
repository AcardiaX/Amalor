package me.acardia.amalor.xposed.systemui

import java.lang.reflect.Modifier

/** Ported from ONextBox AospVolumeGraph (GPL-3.0); never initialize a second SystemUI. */
internal object AospVolumeGraph {
    private const val PREFIX = "com.android.systemui.dagger.DaggerReferenceGlobalRootComponent\$"
    private val optionalNames = listOf("setPip", "setSplitScreen", "setOneHanded", "setBubbles",
        "setTaskViewFactory", "setStartingSurface", "setDisplayAreaHelper", "setRecentTasks",
        "setBackAnimation", "setDesktopMode", "setAppZoomOut", "setAppHandles", "setScrollToTop")

    fun create(host: Any): Any {
        val loader = host.javaClass.classLoader!!
        val hostRoot = requireNotNull(host.javaClass.declaredFields.single {
            it.type.name.endsWith("\$OplusReferenceGlobalRootComponentImpl")
        }.apply { isAccessible = true }.get(host))
        val rootType = Class.forName(PREFIX + "ReferenceGlobalRootComponentImpl", false, loader)
        val rootConstructor = rootType.declaredConstructors.single()
        val root = rootConstructor.apply { isAccessible = true }.newInstance(
            *rootConstructor.parameterTypes.map { argument(hostRoot, it) }.toTypedArray())
        shareProviders(hostRoot, root)
        val type = Class.forName(PREFIX + "ReferenceSysUIComponentImpl", false, loader)
        val constructor = type.declaredConstructors.single()
        require(constructor.parameterCount == 23 && constructor.parameterTypes[0] == rootType)
        var optional = 0
        val args = constructor.parameterTypes.mapIndexed { index, parameter ->
            when {
                index == 0 -> root
                parameter == java.util.Optional::class.java -> field(host, optionalNames[optional++])
                else -> argument(host, parameter)
            }
        }.toTypedArray()
        require(optional == optionalNames.size)
        val graph = constructor.apply { isAccessible = true }.newInstance(*args)
        shareProviders(host, graph)
        require(field(graph, "volumeDialogControllerImplProvider") === field(host, "volumeDialogControllerImplProvider"))
        return requireNotNull(type.getDeclaredMethod("volumeDialogPlugin").apply { isAccessible = true }.invoke(graph))
    }

    private fun argument(host: Any, type: Class<*>): Any {
        if (type.name == "android.content.Context") {
            val provider = field(host, "provideApplicationContextProvider")
            return requireNotNull(provider.javaClass.getMethod("get").apply { isAccessible = true }.invoke(provider))
        }
        val matches = host.javaClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) && it.type == type }
        if (matches.size == 1) return requireNotNull(matches.single().apply { isAccessible = true }.get(host))
        require(type.name.startsWith("com.android.systemui.") && type.simpleName.endsWith("Module")) {
            "Unresolved volume graph argument: ${type.name}"
        }
        return type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
    }

    internal fun shareableProvider(name: String): Boolean = name.endsWith("Provider") &&
        !name.startsWith("factoryProvider") && !name.startsWith("provideVolumeDialog") &&
        name != "volumeDialogComponentProvider"

    internal fun shareProviders(host: Any, target: Any) {
        val original = host.javaClass.declaredFields.associateBy { it.name }
        target.javaClass.declaredFields.filter { shareableProvider(it.name) }.forEach { to ->
            val from = original[to.name] ?: return@forEach
            if (from.type != to.type || Modifier.isStatic(from.modifiers)) return@forEach
            val value = from.apply { isAccessible = true }.get(host) ?: return@forEach
            to.apply { isAccessible = true }.set(target, value)
        }
    }

    internal fun field(instance: Any, name: String): Any = requireNotNull(instance.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(instance))
}
