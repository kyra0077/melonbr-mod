package me.magnum.melonds.ui.gameselect

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.magnum.melonds.domain.model.rom.Rom
import me.magnum.melonds.domain.model.rom.config.RomConfig
import me.magnum.melonds.ui.emulator.EmulatorActivity
import org.json.JSONArray
import java.io.File
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * Launcher screen of the "Pokémon BR" build: one card per game embedded in the APK
 * (assets/games/games.json). Picking a card copies that ROM out of the APK the first
 * time (the emulator needs a real file) and boots it straight away.
 */
class GameSelectActivity : AppCompatActivity() {

    data class EmbeddedGame(
        val label: String,
        val fileName: String,
        val asset: String,
        val icon: ImageBitmap?,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        val games = loadGames(this)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        setContent {
            var preparing by remember { mutableStateOf<Preparing?>(null) }
            var error by remember { mutableStateOf<String?>(null) }
            var lastPlayed by remember { mutableStateOf(prefs.getString(KEY_LAST, null)) }
            val scope = rememberCoroutineScope()

            GameSelectScreen(
                games = games,
                lastPlayed = lastPlayed,
                preparing = preparing,
                error = error,
                onSelect = { game ->
                    if (preparing != null) return@GameSelectScreen
                    error = null
                    scope.launch {
                        try {
                            preparing = Preparing(game.label, 0f)
                            val file = extractGame(game) { p -> preparing = Preparing(game.label, p) }
                            prefs.edit().putString(KEY_LAST, game.fileName).apply()
                            lastPlayed = game.fileName
                            launchGame(game, file)
                        } catch (e: Exception) {
                            error = "Não foi possível abrir ${game.label}: ${e.message ?: e.javaClass.simpleName}"
                        } finally {
                            preparing = null
                        }
                    }
                },
            )
        }
    }

    /** Copies the ROM out of the APK unless an identical-size copy is already there. */
    private suspend fun extractGame(game: EmbeddedGame, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(filesDir, "games").apply { mkdirs() }
        val target = File(dir, game.fileName)
        val size = assets.openFd(game.asset).use { it.length }
        if (target.isFile && target.length() == size) return@withContext target

        val tmp = File(dir, game.fileName + ".tmp")
        assets.open(game.asset).use { input ->
            tmp.outputStream().use { output ->
                val buffer = ByteArray(1 shl 20)
                var copied = 0L
                var lastReport = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    copied += n
                    if (copied - lastReport >= 8L shl 20) {
                        lastReport = copied
                        val p = copied.toFloat() / size
                        withContext(Dispatchers.Main) { onProgress(p) }
                    }
                }
            }
        }
        if (!tmp.renameTo(target)) throw IllegalStateException("falha ao gravar o jogo")
        target
    }

    private fun launchGame(game: EmbeddedGame, file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.games", file)
        val rom = Rom(
            name = game.label,
            developerName = "Nintendo / Game Freak",
            fileName = game.fileName,
            uri = uri,
            parentTreeUri = null, // saves go to the app's own "saves" folder, one .sav per game
            config = RomConfig.default(),
            lastPlayed = null,
            isDsiWareTitle = false,
            retroAchievementsHash = "",
        )
        startActivity(EmulatorActivity.getRomEmulatorActivityIntent(this, rom))
    }

    companion object {
        private const val PREFS = "pokemon_br"
        private const val KEY_LAST = "last_game"

        fun loadGames(context: Context): List<EmbeddedGame> {
            val json = try {
                context.assets.open("games/games.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (_: Exception) {
                return emptyList()
            }
            val array = JSONArray(json)
            return (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                val icon = try {
                    context.assets.open("games/" + o.getString("icon")).use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
                } catch (_: Exception) {
                    null
                }
                EmbeddedGame(
                    label = o.getString("label"),
                    fileName = o.getString("file"),
                    asset = "games/" + o.getString("file"),
                    icon = icon,
                )
            }
        }
    }
}

/** A ROM being copied out of the APK before its first launch. */
internal data class Preparing(val label: String, val progress: Float)

private val Accent = ComposeColor(0xFF78C8FF)

@Composable
private fun GameSelectScreen(
    games: List<GameSelectActivity.EmbeddedGame>,
    lastPlayed: String?,
    preparing: Preparing?,
    error: String?,
    onSelect: (GameSelectActivity.EmbeddedGame) -> Unit,
) {
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(ComposeColor(0xFF101628), ComposeColor(0xFF080A12))))
            .safeDrawingPadding(),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(if (landscape) 16.dp else 40.dp))
            Text(
                text = "Selecione o jogo",
                color = ComposeColor(0xFFF0F2F8),
                fontSize = if (landscape) 26.sp else 30.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(if (landscape) 16.dp else 32.dp))

            if (games.isEmpty()) {
                Text(
                    "Nenhum jogo embutido neste aplicativo.",
                    color = ComposeColor(0xFF969EAF),
                    fontSize = 16.sp,
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(if (landscape) 4 else 2),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth().weight(1f, fill = true).let { if (landscape) it.width(720.dp) else it },
            ) {
                itemsIndexed(games) { _, game ->
                    GameCard(game, highlighted = game.fileName == lastPlayed, onClick = { onSelect(game) })
                }
            }

            if (error != null) {
                Text(error, color = ComposeColor(0xFFFF9C8C), fontSize = 14.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 8.dp))
            }
            Text(
                "Versão pessoal de teste • emulação: melonDS (GPLv3)",
                color = ComposeColor(0xFF5E6678),
                fontSize = 11.sp,
                modifier = Modifier.padding(vertical = 10.dp),
            )
        }

        if (preparing != null) PreparingOverlay(preparing)
    }
}

@Composable
private fun PreparingOverlay(state: Preparing) {
    val label = state.label
    val progress = state.progress
    Box(
        modifier = Modifier.fillMaxSize().background(ComposeColor(0xCC000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text("Preparando $label…", color = ComposeColor.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Text("Só na primeira vez de cada jogo.", color = ComposeColor(0xFF969EAF), fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(progress = progress.coerceIn(0f, 1f), color = Accent, backgroundColor = ComposeColor(0xFF2A3142),
                modifier = Modifier.width(240.dp).height(6.dp).clip(RoundedCornerShape(3.dp)))
        }
    }
}

@Composable
private fun GameCard(game: GameSelectActivity.EmbeddedGame, highlighted: Boolean, onClick: () -> Unit) {
    val lift by animateFloatAsState(if (highlighted) 1.03f else 1f, label = "lift")
    val shape = RoundedCornerShape(14.dp)
    val (small, big) = splitLabel(game.label)
    Column(
        modifier = Modifier
            .scale(lift)
            .clip(shape)
            .background(if (highlighted) ComposeColor(0xFF28365A) else ComposeColor(0xFF1E2230))
            .border(if (highlighted) BorderStroke(2.dp, Accent) else BorderStroke(1.dp, ComposeColor(0xFF2C3244)), shape)
            .clickable(onClick = onClick)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp))
                .background(if (highlighted) ComposeColor(0xFF3A4A6C) else ComposeColor(0xFF2C3242)),
            contentAlignment = Alignment.Center,
        ) {
            game.icon?.let {
                Image(
                    bitmap = it,
                    contentDescription = game.label,
                    filterQuality = FilterQuality.None, // keep the pixel art crisp
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(0.8f),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        if (small.isNotEmpty()) Text(small, color = ComposeColor(0xFF969EAF), fontSize = 12.sp)
        Text(big, color = ComposeColor(0xFFF0F2F8), fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

private fun splitLabel(label: String): Pair<String, String> {
    val sp = label.indexOf(' ')
    if (sp > 0) {
        val first = label.substring(0, sp)
        if (first == "Pokémon" || first == "Pokemon") return first to label.substring(sp + 1)
    }
    return "" to label
}
