package se.partee71.fonder.data.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Varför en säkerhetskopia inte kunde läsas. Skild från ett generellt undantag därför att UI:t
 * ska kunna säga *vilket* av felen det är: "filen kommer från en nyare version av appen" och
 * "filen är trasig" leder till helt olika åtgärder för användaren.
 */
class BackupFormatException(val reason: Reason, val fileVersion: Int? = null) : Exception(
    when (reason) {
        Reason.UNSUPPORTED_VERSION -> "Filformatversion $fileVersion är nyare än ${BackupPayload.FORMAT_VERSION}"
        Reason.UNREADABLE -> "Filen kunde inte tolkas som en säkerhetskopia"
    },
) {
    enum class Reason { UNSUPPORTED_VERSION, UNREADABLE }
}

/**
 * Serialisering av [BackupPayload] till och från filens JSON (SET-6, issue #82). Ren och
 * Android-fri, av samma skäl som `AvanzaJsonParser` — hela formatkontraktet blir testbart i ett
 * vanligt JVM-test, utan enhet eller emulator.
 *
 * Transporten är medvetet inte en del av det här: filen skrivs via SAF i dag och kan skrivas
 * till Drive `appDataFolder` senare (TP-7 steg 2) utan att formatet ändras.
 */
object BackupSerializer {

    /**
     * `encodeDefaults` så filen är självbeskrivande: varje fält står utskrivet även när det har
     * sitt defaultvärde, vilket gör den läsbar för ett öga och låter fältvakten i
     * `BackupSerializerTest` upptäcka ett fält som tappats ur formatet.
     *
     * `ignoreUnknownKeys` gör en fil från en **nyare** app läsbar så länge dess `formatVersion`
     * fortfarande accepteras — okända nycklar är per definition fält den här versionen inte
     * behöver. En version som *tagit bort* eller *omtolkat* ett fält höjer i stället
     * [BackupPayload.FORMAT_VERSION] och fångas av versionskontrollen nedan.
     */
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun encode(payload: BackupPayload): String = json.encodeToString(BackupPayload.serializer(), payload)

    /**
     * Läser en säkerhetskopia. **Fail closed:** versionen kontrolleras innan innehållet avkodas,
     * så en fil från en nyare app avvisas med ett begripligt fel i stället för att läsas in med
     * bara de fält den här versionen råkar känna igen. Allt annat som går fel — trunkerad fil,
     * fel filtyp, omdöpt obligatoriskt fält — blir [BackupFormatException.Reason.UNREADABLE];
     * ingenting skrivs, och anroparen får aldrig en halv payload.
     */
    fun decode(text: String): Result<BackupPayload> {
        val element = runCatching { json.parseToJsonElement(text) }.getOrElse {
            return Result.failure(BackupFormatException(BackupFormatException.Reason.UNREADABLE))
        }

        val fileVersion = runCatching { element.jsonObject["formatVersion"]?.jsonPrimitive?.int }.getOrNull()
            ?: return Result.failure(BackupFormatException(BackupFormatException.Reason.UNREADABLE))

        if (fileVersion > BackupPayload.FORMAT_VERSION) {
            return Result.failure(BackupFormatException(BackupFormatException.Reason.UNSUPPORTED_VERSION, fileVersion))
        }

        return runCatching { json.decodeFromJsonElement(BackupPayload.serializer(), element) }
            .recoverCatching { throw BackupFormatException(BackupFormatException.Reason.UNREADABLE) }
    }
}
