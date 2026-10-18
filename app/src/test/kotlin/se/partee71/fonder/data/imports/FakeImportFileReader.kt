package se.partee71.fonder.data.imports

import android.net.Uri

/** Fejk för [ImportFileReader]: [files] är det som finns att läsa per [Uri]; övriga går inte att öppna. */
class FakeImportFileReader(
    private val files: Map<Uri, Pair<String, ByteArray>> = emptyMap()
) : ImportFileReader {
    override suspend fun readBytes(uri: Uri): ByteArray? = files[uri]?.second

    override suspend fun readNamed(uri: Uri): Pair<String, ByteArray>? = files[uri]
}
