package me.magnum.melonds.domain.model.layout

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pokémon BR: where the DS bottom (touch) screen goes in the default landscape layout.
 * Custom layouts made in the layout editor are not affected.
 */
enum class SecondaryScreenMode {
    /** Bottom screen beside the top one (melonDS default). */
    SIDE_BY_SIDE,
    /** Top screen as large as possible, bottom screen small in a corner on top of it ("minitela"). */
    MINIMAP,
    /** Only the top screen; the swap button shows the bottom screen when the game needs touch. */
    HIDDEN;

    companion object {
        private const val PREFS = "pokemon_br"
        private const val KEY = "landscape_secondary_screen"

        private val state = MutableStateFlow(SIDE_BY_SIDE)
        private var loaded = false

        val current: StateFlow<SecondaryScreenMode> get() = state.asStateFlow()

        fun load(context: Context): SecondaryScreenMode {
            if (!loaded) {
                loaded = true
                val saved = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
                state.value = entries.firstOrNull { it.name == saved } ?: SIDE_BY_SIDE
            }
            return state.value
        }

        fun set(context: Context, mode: SecondaryScreenMode) {
            loaded = true
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, mode.name).apply()
            state.value = mode
        }
    }
}
