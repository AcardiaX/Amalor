package me.acardia.amalor.ui.navigation

import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey

@Serializable
sealed interface AmalorRoute : NavKey {
    @Serializable data object Main : AmalorRoute
    @Serializable data object SystemUiStyle : AmalorRoute
    @Serializable data object StatusBar : AmalorRoute
    @Serializable data object SystemUiOther : AmalorRoute
    @Serializable data object NetworkTypeIcon : AmalorRoute
    @Serializable data object About : AmalorRoute
    @Serializable data object Licenses : AmalorRoute
}
