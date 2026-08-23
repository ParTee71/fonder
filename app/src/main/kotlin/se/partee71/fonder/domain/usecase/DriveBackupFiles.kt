package se.partee71.fonder.domain.usecase

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Namngivning, frågeuttryck och gallringsurval för säkerhetskopiorna i Drives `appDataFolder`
 * (TP-7 steg 2).
 *
 * **Skild från Drive-anropen med flit.** `appDataFolder` är per Google Cloud-*projekt*, inte per
 * Android-app, och Fonder delar projektet `dagboken-711d2` med Dagboken — våra respektive
 * säkerhetskopior ligger alltså i samma dolda mapp. Det som håller isär dem är enbart [PREFIX]
 * och att [QUERY] används vid **varje** listning. En gallring som råkar köras mot en ofiltrerad
 * lista skulle radera den andra appens kopior utan väg tillbaka.
 *
 * Just den logiken får därför inte ligga inbäddad bland `Drive`-anrop som inte går att köra utan
 * Play-tjänster. Den ligger här, som ren Kotlin, och är enhetstestad (regel 1 + regel 2).
 */
object DriveBackupFiles {

    /** Namnrymden som skiljer Fonders kopior från Dagbokens i den delade mappen. */
    const val PREFIX = "fonder-backup-"

    /**
     * Antal kopior som behålls. Äldre gallras — en säkerhetskopia är till för att återställa
     * *något*, och en obegränsad historik kostar kvot utan att skydda mer.
     */
    const val KEEP_COUNT = 5

    /**
     * `trashed = false` är inte kosmetiskt: papperskorgade filer ligger kvar i `appDataFolder`
     * och matchar annars namnfrågan, så en raderad kopia skulle kunna räknas som den senaste.
     */
    const val QUERY = "name contains '$PREFIX' and trashed = false"

    private val timestampFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")

    fun fileName(at: LocalDateTime): String = "$PREFIX${at.format(timestampFormat)}.json"

    /** Sant om [name] hör till Fonder. Sista spärren innan en fil rörs. */
    fun isOurs(name: String): Boolean = name.startsWith(PREFIX)

    /**
     * Id:n för de kopior som ska raderas: allt utom de [keep] senaste.
     *
     * [files] förväntas komma från en [QUERY]-filtrerad listning sorterad med nyast först, men
     * funktionen **litar inte på det** — den filtrerar om på [isOurs] och sorterar själv. Det är
     * medvetet redundant: kostnaden är noll, och alternativet är att en framtida ändring av
     * anropssidan tyst gör gallringen farlig igen.
     */
    fun staleFileIds(
        files: List<DriveBackupFile>,
        keep: Int = KEEP_COUNT,
    ): List<String> = files
        .filter { isOurs(it.name) }
        .sortedByDescending { it.createdTime }
        .drop(keep.coerceAtLeast(0))
        .map { it.id }
}

/** En säkerhetskopia i Drives `appDataFolder`, som [DriveBackupFiles] resonerar om. */
data class DriveBackupFile(
    val id: String,
    val name: String,
    /** RFC 3339 från Drive. Jämförs som sträng — formatet är fast bredd och sorterar kronologiskt. */
    val createdTime: String,
)
