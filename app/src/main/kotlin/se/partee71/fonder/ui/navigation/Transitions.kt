package se.partee71.fonder.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.navigation3.scene.Scene

/**
 * Skärmbyten – definierade bara här: en överbländning på 700 ms åt båda hållen, även när bakåt
 * dras med systemets prediktiva bakåtgest. Samma rörelse som Navigation Composes `NavHost`
 * hade som standard, så bytet till Navigation 3 inte syns.
 */
object Transitions {
    private const val DURATION_MS = 700

    private val crossfade: AnimatedContentTransitionScope<Scene<AppKey>>.() -> ContentTransform = {
        fadeIn(tween(DURATION_MS)) togetherWith fadeOut(tween(DURATION_MS))
    }

    val forward: AnimatedContentTransitionScope<Scene<AppKey>>.() -> ContentTransform = crossfade

    val back: AnimatedContentTransitionScope<Scene<AppKey>>.() -> ContentTransform = crossfade

    val predictiveBack: AnimatedContentTransitionScope<Scene<AppKey>>.(Int) -> ContentTransform = { crossfade() }
}
