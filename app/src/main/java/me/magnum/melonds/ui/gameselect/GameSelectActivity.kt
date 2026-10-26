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
import me.magnum.melonds.domain.model.layout.SecondaryScreenMode
import me.magnum.melonds.domain.model.rom.Rom
import me.magnum.melonds.domain.model.rom.config.RomConfig
import me.magnum.melonds.ui.emulator.EmulatorActivity
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateMapOf
import dagger.hilt.android.AndroidEntryPoint
import me.magnum.melonds.common.Permission
import me.magnum.melonds.common.UriPermissionManager
import me.magnum.melonds.common.contracts.DirectoryPickerContract
import me.magnum.melonds.domain.model.RomScanningStatus
import me.magnum.melonds.domain.repositories.RomsRepository
import me.magnum.melonds.domain.repositories.SettingsRepository
import me.magnum.melonds.impl.RomIconProvider
import org.json.JSONArray
import java.io.File
import javax.inject.Inject
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * Launcher screen of MelonBR Mod.
 *
 * Personal build (ROMs embedded, -PembeddedGamesDir): one card per game in assets/games/games.json. Picking a
 * card copies that ROM out of the APK the first time (the emulator needs a real file).
 *
 * Regular build (no ROMs inside): the player's own games from the ROMs folder
 * (Documents/MelonBR Mod/ROMs), which the app creates and can open in the file manager.
 */
@AndroidEntryPoint
class GameSelectActivity : AppCompatActivity() {

    data class EmbeddedGame(
        val label: String,
        val fileName: String,
        val asset: String,
        val icon: ImageBitmap?,
        val rom: Rom? = null, // set for games found in the ROMs folder
    )

    @Inject lateinit var romsRepository: RomsRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var romIconProvider: RomIconProvider
    @Inject lateinit var uriPermissionManager: UriPermissionManager

    private val folderAccess = mutableStateOf(false)
    private var isFolderMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        val embedded = loadGames(this)
        isFolderMode = embedded.isEmpty()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (isFolderMode) {
            RomsFolder.ensureExists()
            folderAccess.value = hasFolderAccess()
        }

        setContent {
            val romsFlow = remember { if (isFolderMode) romsRepository.getRoms() else kotlinx.coroutines.flow.flowOf(emptyList()) }
            val folderRoms by romsFlow.collectAsState(initial = emptyList())
            val scanning by romsRepository.getRomScanningStatus().collectAsState()
            val icons = remember { mutableStateMapOf<String, ImageBitmap?>() }
            LaunchedEffect(folderRoms) {
                folderRoms.forEach { rom ->
                    val key = rom.uri.toString()
                    if (!icons.containsKey(key)) icons[key] = runCatching { romIconProvider.getRomIcon(rom)?.asImageBitmap() }.getOrNull()
                }
            }
            val games = if (isFolderMode) {
                folderRoms.filter { !it.isDsiWareTitle }
                    .sortedBy { cleanRomName(it.config.customName ?: it.name).lowercase() }
                    .map { rom ->
                        EmbeddedGame(
                            label = cleanRomName(rom.config.customName ?: rom.name),
                            fileName = rom.fileName,
                            asset = "",
                            icon = icons[rom.uri.toString()],
                            rom = rom,
                        )
                    }
            } else {
                embedded
            }
            val pickFolder = rememberLauncherForActivityResult(DirectoryPickerContract(Permission.READ_WRITE)) { uri ->
                if (uri != null) {
                    runCatching { uriPermissionManager.persistDirectoryPermissions(uri, Permission.READ_WRITE) }
                    settingsRepository.addRomSearchDirectory(uri)
                    folderAccess.value = hasFolderAccess()
                    romsRepository.rescanRoms()
                }
            }

            var preparing by remember { mutableStateOf<Preparing?>(null) }
            var error by remember { mutableStateOf<String?>(null) }
            var lastPlayed by remember { mutableStateOf(prefs.getString(KEY_LAST, null)) }
            var screenMode by remember { mutableStateOf(SecondaryScreenMode.load(this)) }
            val scope = rememberCoroutineScope()

            GameSelectScreen(
                games = games,
                folder = if (isFolderMode) FolderState(
                    path = currentFolderLabel(),
                    hasAccess = folderAccess.value,
                    scanning = scanning == RomScanningStatus.SCANNING,
                    onOpenFolder = {
                        if (!RomsFolder.open(this)) {
                            Toast.makeText(this, "Abra o gerenciador de arquivos e vá até ${RomsFolder.DISPLAY_PATH}", Toast.LENGTH_LONG).show()
                        }
                    },
                    onGrantAccess = { pickFolder.launch(RomsFolder.documentUri()) },
                    onRefresh = { romsRepository.rescanRoms() },
                ) else null,
                lastPlayed = lastPlayed,
                preparing = preparing,
                error = error,
                screenMode = screenMode,
                onScreenModeChange = { mode ->
                    SecondaryScreenMode.set(this, mode)
                    screenMode = mode
                },
                onSelect = { game ->
                    if (preparing != null) return@GameSelectScreen
                    error = null
                    game.rom?.let { rom ->
                        prefs.edit().putString(KEY_LAST, game.fileName).apply()
                        lastPlayed = game.fileName
                        startActivity(EmulatorActivity.getRomEmulatorActivityIntent(this, rom))
                        return@GameSelectScreen
                    }
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

    override fun onResume() {
        super.onResume()
        // Back from the file manager: pick up games copied in the meantime.
        if (isFolderMode) {
            folderAccess.value = hasFolderAccess()
            if (folderAccess.value) romsRepository.rescanRoms()
        }
    }

    private fun hasFolderAccess(): Boolean {
        val dirs = settingsRepository.getRomSearchDirectories()
        if (dirs.isEmpty()) return false
        val granted = contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }
        return dirs.any { it in granted }
    }

    /** "Documentos/MelonBR Mod/ROMs", or the folder the player picked instead. */
    private fun currentFolderLabel(): String {
        val dir = settingsRepository.getRomSearchDirectories().firstOrNull() ?: return RomsFolder.DISPLAY_PATH
        if (RomsFolder.isExpectedTree(dir)) return RomsFolder.DISPLAY_PATH
        val id = runCatching { DocumentsContract.getTreeDocumentId(dir) }.getOrNull() ?: return RomsFolder.DISPLAY_PATH
        return id.substringAfter(':').ifBlank { "armazenamento interno" }
            .replaceFirst("Documents", "Documentos").replaceFirst("Download", "Downloads")
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

        /** "POKéMON Black Version 2" -> "Pokémon Black 2" (banner titles are what the ROM list gives). */
        fun cleanRomName(name: String): String {
            return name.replace(Regex("(?i)pok[eé]mon"), "Pokémon")
                .replace(Regex("(?i)\\s+version(\\s|$)"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

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

/** Shareable build: where the ROMs folder is and what the player can do with it. */
internal data class FolderState(
    val path: String,
    val hasAccess: Boolean,
    val scanning: Boolean,
    val onOpenFolder: () -> Unit,
    val onGrantAccess: () -> Unit,
    val onRefresh: () -> Unit,
)

/** A ROM being copied out of the APK before its first launch. */
internal data class Preparing(val label: String, val progress: Float)

private val Accent = ComposeColor(0xFF78C8FF)

@Composable
private fun GameSelectScreen(
    games: List<GameSelectActivity.EmbeddedGame>,
    folder: FolderState?,
    lastPlayed: String?,
    preparing: Preparing?,
    error: String?,
    screenMode: SecondaryScreenMode,
    onScreenModeChange: (SecondaryScreenMode) -> Unit,
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

            if (folder != null) {
                FolderPanel(folder, gamesFound = games.size)
                Spacer(Modifier.height(16.dp))
            } else if (games.isEmpty()) {
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
            ScreenModePicker(screenMode, onScreenModeChange)
            Text(
                if (folder != null) "Versão de teste • não inclui jogos: use as suas próprias cópias • melonDS (GPLv3)"
                else "Versão pessoal de teste • emulação: melonDS (GPLv3)",
                textAlign = TextAlign.Center,
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

@Composable
private fun ScreenModePicker(mode: SecondaryScreenMode, onChange: (SecondaryScreenMode) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 12.dp)) {
        Text("Tela de toque com o celular na horizontal", color = ComposeColor(0xFF969EAF), fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                SecondaryScreenMode.SIDE_BY_SIDE to "Lado a lado",
                SecondaryScreenMode.MINIMAP to "Minitela",
                SecondaryScreenMode.HIDDEN to "Oculta",
            ).forEach { (m, label) ->
                val selected = m == mode
                val shape = RoundedCornerShape(50)
                Text(
                    label,
                    color = if (selected) ComposeColor(0xFF0B1220) else ComposeColor(0xFFD0D5E0),
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier
                        .clip(shape)
                        .background(if (selected) Accent else ComposeColor(0xFF232838))
                        .border(1.dp, if (selected) Accent else ComposeColor(0xFF343B50), shape)
                        .clickable { onChange(m) }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
    }
}

@Composable
private fun FolderPanel(folder: FolderState, gamesFound: Int) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ComposeColor(0xFF161B2B))
            .border(1.dp, ComposeColor(0xFF2C3244), shape)
            .padding(14.dp),
    ) {
        Text("Pasta dos jogos", color = ComposeColor(0xFF969EAF), fontSize = 12.sp)
        Text(folder.path, color = ComposeColor(0xFFF0F2F8), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        val hint = when {
            !folder.hasAccess -> "Copie seus arquivos .nds para essa pasta e toque em \"Permitir acesso\" (só na primeira vez)."
            folder.scanning -> "Procurando jogos…"
            gamesFound == 0 -> "Nenhum jogo encontrado. Copie seus arquivos .nds (ou .zip) para essa pasta e toque em \"Atualizar\"."
            else -> "$gamesFound ${if (gamesFound == 1) "jogo encontrado" else "jogos encontrados"}. Para adicionar mais, copie para essa pasta."
        }
        Text(hint, color = ComposeColor(0xFFB4BBCB), fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelButton("Abrir pasta", primary = folder.hasAccess, onClick = folder.onOpenFolder)
            if (folder.hasAccess) {
                PanelButton("Atualizar", primary = false, onClick = folder.onRefresh)
            } else {
                PanelButton("Permitir acesso", primary = true, onClick = folder.onGrantAccess)
            }
        }
    }
}

@Composable
private fun PanelButton(label: String, primary: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        label,
        color = if (primary) ComposeColor(0xFF0B1220) else ComposeColor(0xFFD0D5E0),
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(shape)
            .background(if (primary) Accent else ComposeColor(0xFF232838))
            .border(1.dp, if (primary) Accent else ComposeColor(0xFF343B50), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}
