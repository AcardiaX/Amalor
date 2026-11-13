package me.acardia.amalor.ui.navigation

import top.yukonga.miuix.kmp.nav.core.NavController

/** Settings destinations have one stable content key and one entry per back stack. */
internal fun NavController.navigateTo(route: AmalorRoute) {
    // Read the live stack at click time, not the last route seen by a previous composition.
    if (backStack.lastOrNull() == route) return
    if (route in backStack) {
        popUntil { it == route }
    } else {
        push(route)
    }
}
