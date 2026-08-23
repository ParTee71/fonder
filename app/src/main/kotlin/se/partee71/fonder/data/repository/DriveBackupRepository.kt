package se.partee71.fonder.data.repository

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import se.partee71.fonder.data.auth.AuthRepository
import se.partee71.fonder.domain.usecase.DriveBackupFile
import se.partee71.fonder.domain.usecase.DriveBackupFiles
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import com.google.api.services.drive.model.File as DriveFile

/**
 * Utfallet av en Drive-operation (TP-7 steg 2). Egen typ i stället för `Result`, eftersom två av
 * lägena varken är framgång eller fel: **ingen är inloggad** är ett tillstånd appen ska tåla, och
 * **behöver auktorisering** kräver att UI:t startar en `PendingIntent` — något ett `Throwable`
 * inte kan bära.
 */
sealed interface DriveResult<out T> {
    data class Success<T>(val value: T) : DriveResult<T>
    data class Error(val message: String) : DriveResult<Nothing>

    /** Ingen inloggad användare. Inte ett fel — bakgrundsjobbet hoppar över tyst. */
    data object NoAccount : DriveResult<Nothing>

    /** Inloggad, men ingen säkerhetskopia finns i mappen än. */
    data object NoBackupFound : DriveResult<Nothing>

    /**
     * Drive-scopet är inte beviljat. [pendingIntent] måste startas från **aktiviteten** — se
     * `SettingsScreen`. Inloggning (TP-6) räcker inte: `drive.appdata` begärs separat, första
     * gången backupen används, och utan det svarar Drive 403.
     */
    data class NeedsAuthorization(val pendingIntent: PendingIntent) : DriveResult<Nothing>
}

/**
 * Molnbackup mot Drives `appDataFolder` (TP-7 steg 2) — en **transport**, inte en andra
 * [BackupRepository].
 *
 * `BackupRepository` lämnas orört med flit: dess kontrakt är en `String`, och det var precis
 * därför. Drive behöver mer än `export`/`restore` — lista, hämta senaste, och en
 * auktoriseringsväg som kräver UI — och det hör inte hemma i formatets kontrakt. Anroparen
 * komponerar i stället de två:
 *
 * ```
 * säkerhetskopiera:  backupRepository.export()    →  driveBackup.upload(json)
 * återställ:         driveBackup.downloadLatest() →  backupRepository.restore(json)
 * ```
 *
 * Formatet (`BackupPayload`/`BackupSerializer`) ändras alltså inte av att transporten byts.
 */
interface DriveBackupRepository {
    /** Skriver [json] som en ny kopia och gallrar äldre. Returnerar filens id. */
    suspend fun upload(json: String): DriveResult<String>

    /** Hämtar innehållet i den senaste kopian. [DriveResult.NoBackupFound] om mappen är tom. */
    suspend fun downloadLatest(): DriveResult<String>

    /** Kopiorna i mappen, nyast först — bara Fonders (se [DriveBackupFiles]). */
    suspend fun list(): DriveResult<List<DriveBackupFile>>
}

@Singleton
class GoogleDriveBackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
) : DriveBackupRepository {

    private sealed interface Authorization {
        data class Token(val accessToken: String) : Authorization
        data class NeedsUser(val pendingIntent: PendingIntent) : Authorization
        data class Failed(val message: String) : Authorization
    }

    /**
     * Begär `drive.appdata` för det inloggade kontot. Scopet är skilt från inloggningen och
     * begärs först när backupen faktiskt används — appen ber alltså aldrig om Drive-åtkomst
     * av en användare som bara loggat in.
     */
    private suspend fun authorize(): Authorization {
        val accountHint = authRepository.currentUser.first()?.email?.let { Account(it, "com.google") }
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DriveScopes.DRIVE_APPDATA)))
            .apply { accountHint?.let { setAccount(it) } }
            .build()

        return suspendCancellableCoroutine { cont ->
            Identity.getAuthorizationClient(context)
                .authorize(request)
                .addOnSuccessListener { result: AuthorizationResult ->
                    cont.resume(
                        when {
                            result.hasResolution() -> Authorization.NeedsUser(result.pendingIntent!!)
                            result.accessToken != null -> Authorization.Token(result.accessToken!!)
                            else -> Authorization.Failed("Ingen åtkomsttoken returnerades")
                        },
                    )
                }
                .addOnFailureListener { e ->
                    cont.resume(Authorization.Failed(e.message ?: "Auktoriseringen misslyckades"))
                }
        }
    }

    private fun driveFor(accessToken: String): Drive {
        val initializer = HttpRequestInitializer { it.headers.authorization = "Bearer $accessToken" }
        return Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), initializer)
            .setApplicationName(APP_NAME)
            .build()
    }

    private fun messageFor(e: GoogleJsonResponseException): String {
        val error = e.details?.errors?.firstOrNull()
        return "Drive ${e.statusCode} (${error?.reason ?: "?"}): ${error?.message ?: e.message}"
    }

    /**
     * Gemensam inramning: hoppa av om ingen är inloggad, auktorisera, kör på IO och mappa fel.
     * Ingen kastad exception tar sig ut härifrån — samma princip som [BackupRepository].
     */
    private suspend fun <T> withDrive(block: (Drive) -> DriveResult<T>): DriveResult<T> {
        if (authRepository.currentUser.first() == null) return DriveResult.NoAccount

        return withContext(Dispatchers.IO) {
            when (val auth = authorize()) {
                is Authorization.NeedsUser -> DriveResult.NeedsAuthorization(auth.pendingIntent)
                is Authorization.Failed -> DriveResult.Error(auth.message)
                is Authorization.Token -> try {
                    block(driveFor(auth.accessToken))
                } catch (e: GoogleJsonResponseException) {
                    DriveResult.Error(messageFor(e))
                } catch (e: Exception) {
                    DriveResult.Error(e.message ?: "Okänt fel mot Drive")
                }
            }
        }
    }

    /** Alltid [DriveBackupFiles.QUERY] — se den klassens KDoc för varför det inte är valfritt. */
    private fun Drive.ourBackups(pageSize: Int? = null): List<DriveBackupFile> =
        files().list()
            .setSpaces(APP_DATA_FOLDER)
            .setQ(DriveBackupFiles.QUERY)
            .setOrderBy("createdTime desc")
            .apply { pageSize?.let { setPageSize(it) } }
            .setFields("files(id,name,createdTime)")
            .execute()
            .files
            .orEmpty()
            .map { DriveBackupFile(it.id, it.name, it.createdTime?.toString().orEmpty()) }

    override suspend fun list(): DriveResult<List<DriveBackupFile>> =
        withDrive { drive -> DriveResult.Success(drive.ourBackups()) }

    override suspend fun upload(json: String): DriveResult<String> = withDrive { drive ->
        val metadata = DriveFile().apply {
            name = DriveBackupFiles.fileName(LocalDateTime.now())
            parents = listOf(APP_DATA_FOLDER)
        }
        val media = ByteArrayContent("application/json", json.toByteArray())
        val created = drive.files().create(metadata, media).setFields("id").execute()

        prune(drive)
        DriveResult.Success(created.id)
    }

    override suspend fun downloadLatest(): DriveResult<String> = withDrive { drive ->
        val latest = drive.ourBackups(pageSize = 1).firstOrNull()
            ?: return@withDrive DriveResult.NoBackupFound

        val content = drive.files().get(latest.id)
            .executeMediaAsInputStream()
            .bufferedReader()
            .readText()

        DriveResult.Success(content)
    }

    /**
     * Gallringen är den enda operationen som **raderar**, och mappen delas med Dagboken — urvalet
     * görs därför av [DriveBackupFiles.staleFileIds], som är ren och enhetstestad. `runCatching`:
     * en misslyckad gallring får aldrig fälla en lyckad säkerhetskopiering.
     */
    private fun prune(drive: Drive) {
        runCatching {
            DriveBackupFiles.staleFileIds(drive.ourBackups())
                .forEach { drive.files().delete(it).execute() }
        }
    }

    private companion object {
        const val APP_DATA_FOLDER = "appDataFolder"
        const val APP_NAME = "Fonder"
    }
}
