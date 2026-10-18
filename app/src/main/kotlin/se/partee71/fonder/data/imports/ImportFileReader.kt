package se.partee71.fonder.data.imports

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import se.partee71.fonder.di.IoDispatcher
import javax.inject.Inject

/**
 * Läser en fil som användaren valt i filväljaren (Storage Access Framework). Abstraherat bort från
 * [android.content.ContentResolver] så att importens ViewModels kan enhetstestas med en fejk (samma
 * princip som [PdfTextExtractor]).
 */
interface ImportFileReader {
    /** Hela filens innehåll, eller null om den inte gick att öppna. */
    suspend fun readBytes(uri: Uri): ByteArray?

    /** Filens visningsnamn och innehåll, eller null om den inte gick att öppna. */
    suspend fun readNamed(uri: Uri): Pair<String, ByteArray>?
}

/** Läser via appens [android.content.ContentResolver], på I/O-dispatchern. */
class ContentResolverImportFileReader @Inject constructor(
    @ApplicationContext private val context: Context,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ImportFileReader {

    override suspend fun readBytes(uri: Uri): ByteArray? = withContext(io) {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }

    override suspend fun readNamed(uri: Uri): Pair<String, ByteArray>? = withContext(io) {
        val resolver = context.contentResolver
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
        val name = resolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
        } ?: uri.lastPathSegment.orEmpty()
        name to bytes
    }
}
