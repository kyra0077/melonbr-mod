package me.magnum.melonds.ui.gameselect

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/**
 * MelonBR Mod: the folder where players put their own
 * games. It lives in shared storage so any file manager can reach it:
 * Documents/MelonBR Mod/ROMs (shown in Portuguese file managers as "Documentos").
 */
object RomsFolder {
    const val RELATIVE_PATH = "MelonBR Mod/ROMs"
    const val DISPLAY_PATH = "Documentos/MelonBR Mod/ROMs"
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val DOCUMENT_ID = "primary:Documents/$RELATIVE_PATH"

    fun file(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), RELATIVE_PATH)

    /** Creates the folder (Android 11+ lets any app create folders under Documents). */
    fun ensureExists(): Boolean {
        val dir = file()
        return dir.isDirectory || dir.mkdirs()
    }

    /** Document URI of the folder, used to open it in the file manager and as the picker's start. */
    fun documentUri(): Uri = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, DOCUMENT_ID)

    /** Tree URI the player is expected to grant, to check whether access was already given. */
    fun isExpectedTree(uri: Uri): Boolean {
        return uri.authority == EXTERNAL_STORAGE_AUTHORITY &&
            runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() == DOCUMENT_ID
    }

    /**
     * Opens the folder in a file manager. Tries the system "Files" app first, which understands
     * folder URIs on every Android version this app supports, then any app that does.
     */
    fun open(context: Context): Boolean {
        ensureExists()
        val uri = documentUri()
        val candidates = listOf("com.google.android.documentsui", "com.android.documentsui", null)
        for (pkg in candidates) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (pkg != null) setPackage(pkg)
            }
            try {
                context.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return false
    }
}
