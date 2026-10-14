package se.partee71.fonder.data.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.data.datastore.ThemeMode
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.domain.model.DownturnReaction
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.PrimaryGoal
import se.partee71.fonder.domain.model.RiskProfile
import se.partee71.fonder.domain.model.RiskProfileAnswers
import se.partee71.fonder.domain.model.SuggestionDismissal
import se.partee71.fonder.domain.model.SuggestionKind
import se.partee71.fonder.domain.model.SuggestionRecord
import se.partee71.fonder.domain.model.SwitchWatch
import se.partee71.fonder.domain.model.SwitchWatchCandidate
import se.partee71.fonder.domain.model.SwitchWatchCandidateSource
import se.partee71.fonder.domain.model.SwitchWatchCloseReason
import se.partee71.fonder.domain.model.TimeHorizon
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType

/**
 * Filformatet för säkerhetskopian (SET-6, issue #82). Rent JVM-test utan Android: hela
 * formatkontraktet ligger i [BackupSerializer], så rundturen kan bevisas utan emulator.
 */
class BackupSerializerTest {

    /**
     * En payload där **varje** fält är ifyllt med ett värde som skiljer sig från defaulten, och
     * där de nullbara fälten dessutom förekommer i båda lägena. Ett fält som tappas i
     * serialiseringen syns då som en skillnad, inte som ett råkat likadant defaultvärde.
     */
    private fun fullPayload() = BackupPayload(
        exportedAtEpochMillis = 1_754_300_000_000,
        chosenBenchmarkIsin = "SE0011527613",
        funds = listOf(
            Fund(fundId = "SHB0000442", name = "Fond A", currency = "SEK", isin = "SE0000582033", fondlistaFundId = "SHB0000442"),
            Fund(fundId = "LU0055631609", name = "Fond utan koder", currency = "USD", isin = null, fondlistaFundId = null),
        ),
        transactions = listOf(
            Transaction(id = 7, fundId = "SHB0000442", type = TransactionType.KOP, epochDay = 19_000, shares = 12.5, pricePerShare = 178.25, fee = 25.0),
            Transaction(id = 8, fundId = "SHB0000442", type = TransactionType.SALJ, epochDay = 19_500, shares = 4.0, pricePerShare = 201.5, fee = 0.0),
        ),
        suggestionRecords = listOf(
            SuggestionRecord(
                id = 3, suggestedAtEpochDay = 19_800, planIndex = 0,
                sellIsin = "SE0000582033", buyIsin = "SE0001466368",
                sellNavAtSuggestion = 201.5, buyNavAtSuggestion = 95.25,
                switchValueKr = 4_200.0, followed = true, batchEpochMillis = 1_754_200_000_000,
            ),
            SuggestionRecord(
                id = 4, suggestedAtEpochDay = 19_800, planIndex = 1,
                sellIsin = "SE0000582033", buyIsin = "SE0004617590",
                sellNavAtSuggestion = 201.5, buyNavAtSuggestion = 55.0,
                switchValueKr = null, followed = false, batchEpochMillis = 1_754_200_000_000,
            ),
            SuggestionRecord(
                id = 5, suggestedAtEpochDay = 19_400, planIndex = 0,
                sellIsin = "SE0000582033", buyIsin = "SE0005991445",
                sellNavAtSuggestion = 190.0, buyNavAtSuggestion = 70.0,
                switchValueKr = 1_000.0, followed = null, batchEpochMillis = 0,
                kind = SuggestionKind.FEE,
            ),
        ),
        switchWatches = listOf(
            SwitchWatch(
                id = 2,
                sellIsin = "SE0000582033",
                sellFundName = "Fond A",
                soldAtEpochDay = 19_900,
                proceedsKr = 12_500.0,
                targetLevel = 4,
                sourceRecordId = 3,
                // Startad ur säljtransaktion 8 (BYT-1); inget köp än.
                sellTransactionId = 8,
                buyTransactionId = null,
                // Användaren har valt bara indexfonder (ANA-13, issue #150).
                indexOnly = true,
                candidates = listOf(
                    SwitchWatchCandidate(
                        id = 5, watchId = 2, isin = "SE0001466368", name = "Kandidat A",
                        navAtStart = 95.25, navAtStartEpochDay = 19_900,
                        source = SwitchWatchCandidateSource.AUTO, position = 0,
                    ),
                    SwitchWatchCandidate(
                        id = 6, watchId = 2, isin = "SE0004617590", name = "Kandidat B",
                        navAtStart = null, navAtStartEpochDay = null,
                        source = SwitchWatchCandidateSource.MANUELL, position = 1,
                    ),
                ),
            ),
            SwitchWatch(
                id = 3,
                sellIsin = "SE0005991445",
                sellFundName = "Fond C",
                soldAtEpochDay = 19_500,
                proceedsKr = null,
                targetLevel = null,
                sourceRecordId = null,
                closedAtEpochDay = 19_505,
                boughtIsin = "SE0001466368",
                closeReason = SwitchWatchCloseReason.KOPT,
                // Från före 0.67.0: säljet okänt, köpet kvitterat med transaktion 7.
                sellTransactionId = null,
                buyTransactionId = 7,
            ),
        ),
        suggestionDismissals = listOf(
            SuggestionDismissal(sellIsin = "SE0000582033", buyIsin = "SE0001466368", dismissedAtEpochDay = 19_810),
            SuggestionDismissal(sellIsin = "SE0000582033", buyIsin = "SE0005991445", kind = SuggestionKind.FEE, dismissedAtEpochDay = 19_420),
        ),
        riskProfile = RiskProfile(
            targetAllocation = mapOf(3 to 0.25, 4 to 0.5, 5 to 0.25),
            answers = RiskProfileAnswers(
                horizon = TimeHorizon.SJU_TILL_15_AR,
                reaction = DownturnReaction.KOPER_MER,
                goal = PrimaryGoal.MAXIMAL_TILLVAXT,
            ),
            targetRiskLevel = 4,
        ),
        accountType = AccountType.ISK_KF,
        themeMode = ThemeMode.DARK,
    )

    @Test
    fun `full rundtur bevarar varje falt`() {
        val original = fullPayload()

        val restored = BackupSerializer.decode(BackupSerializer.encode(original)).getOrThrow()

        assertEquals(original, restored)
    }

    @Test
    fun `followed bevaras i alla tre lagen och switchValueKr som null`() {
        // Det är den enda kolumnen i suggestion_records som är ett *val* och inte en mätning —
        // tappas den kan facit (SET-5) aldrig skilja ett följt råd från ett bara givet.
        val restored = BackupSerializer.decode(BackupSerializer.encode(fullPayload())).getOrThrow()

        assertEquals(listOf(true, false, null), restored.suggestionRecords.map { it.followed })
        assertEquals(listOf(4_200.0, null, 1_000.0), restored.suggestionRecords.map { it.switchValueKr })
        assertEquals(listOf(1_754_200_000_000, 1_754_200_000_000, 0), restored.suggestionRecords.map { it.batchEpochMillis })
        // Sorten måste följa med (issue #91): utan den går ett återställt `followed` inte att
        // tolka, eftersom de två sorterna mäts var för sig i facit.
        assertEquals(
            listOf(SuggestionKind.RISK_PLAN, SuggestionKind.RISK_PLAN, SuggestionKind.FEE),
            restored.suggestionRecords.map { it.kind },
        )
    }

    @Test
    fun `en fil utan kind lases som bytesplansbyten`() {
        // Filer skrivna före issue #91 saknar nyckeln helt. Defaulten är den enda rimliga
        // tolkningen — varje rad som fanns då var ett bytesplansbyte — och en återställning ska
        // aldrig avvisa filen eller gissa något annat.
        val utanKind = BackupSerializer.encode(fullPayload())
            .replace(Regex(",\\s*\"kind\": \"[A-Z_]+\""), "")

        val restored = BackupSerializer.decode(utanKind).getOrThrow()

        assertEquals(3, restored.suggestionRecords.size)
        assertTrue(restored.suggestionRecords.all { it.kind == SuggestionKind.RISK_PLAN })
    }

    @Test
    fun `riskprofilens malfordelning och legacy-falt overlever bada`() {
        // targetRiskLevel är #68:s gamla skalärmodell och läses fortfarande som fallback —
        // tappas det i backupen försvinner en gammal profil spårlöst vid en återställning.
        val restored = BackupSerializer.decode(BackupSerializer.encode(fullPayload())).getOrThrow()

        assertEquals(mapOf(3 to 0.25, 4 to 0.5, 5 to 0.25), restored.riskProfile?.targetAllocation)
        assertEquals(4, restored.riskProfile?.targetRiskLevel)
        assertEquals(TimeHorizon.SJU_TILL_15_AR, restored.riskProfile?.answers?.horizon)
    }

    @Test
    fun `tom payload ar en giltig rundtur`() {
        val empty = BackupPayload(exportedAtEpochMillis = 1)

        val restored = BackupSerializer.decode(BackupSerializer.encode(empty)).getOrThrow()

        assertEquals(empty, restored)
        assertNull(restored.riskProfile)
        assertNull(restored.accountType)
    }

    /**
     * Vaktpost mot **tyst fältförlust**: läggs ett fält till i någon av modellerna utan att
     * tänka på backupen går det här testet sönder, i stället för att fältet försvinner ur varje
     * framtida säkerhetskopia utan att någon märker det (regel 1). Uppdatera nyckelmängden
     * medvetet — och se till att fältet faktiskt hör hemma i kontraktet.
     */
    @Test
    fun `formatets falt ar last — ett nytt falt maste tas med medvetet`() {
        val root = Json.parseToJsonElement(BackupSerializer.encode(fullPayload())).jsonObject

        assertEquals(
            setOf(
                "formatVersion", "exportedAtEpochMillis", "funds", "transactions",
                "suggestionRecords", "switchWatches", "suggestionDismissals", "riskProfile", "accountType",
                "themeMode", "chosenBenchmarkIsin",
            ),
            root.keys,
        )
        assertEquals(
            setOf("sellIsin", "buyIsin", "kind", "dismissedAtEpochDay"),
            root.getValue("suggestionDismissals").jsonArray.first().jsonObject.keys,
        )
        assertEquals(
            setOf("fundId", "name", "currency", "isin", "fondlistaFundId"),
            root.getValue("funds").jsonArray.first().jsonObject.keys,
        )
        assertEquals(
            setOf("id", "fundId", "type", "epochDay", "shares", "pricePerShare", "fee"),
            root.getValue("transactions").jsonArray.first().jsonObject.keys,
        )
        assertEquals(
            setOf(
                "id", "suggestedAtEpochDay", "planIndex", "sellIsin", "buyIsin",
                "sellNavAtSuggestion", "buyNavAtSuggestion", "switchValueKr", "followed",
                "batchEpochMillis", "kind",
            ),
            root.getValue("suggestionRecords").jsonArray.first().jsonObject.keys,
        )
        assertEquals(
            setOf(
                "id", "sellIsin", "sellFundName", "soldAtEpochDay", "proceedsKr", "targetLevel",
                "sourceRecordId", "closedAtEpochDay", "boughtIsin", "closeReason", "candidates",
                "sellTransactionId", "buyTransactionId", "indexOnly",
            ),
            root.getValue("switchWatches").jsonArray.first().jsonObject.keys,
        )
        assertEquals(
            setOf("id", "watchId", "isin", "name", "navAtStart", "navAtStartEpochDay", "source", "position"),
            root.getValue("switchWatches").jsonArray.first().jsonObject
                .getValue("candidates").jsonArray.first().jsonObject.keys,
        )
        assertEquals(
            setOf("targetAllocation", "answers", "targetRiskLevel"),
            root.getValue("riskProfile").jsonObject.keys,
        )
    }

    @Test
    fun `kopplingen till salj- och koptransaktionen overlever i bada lagen`() {
        // BYT-1: länken är det som gör bytet till ett objekt — tappas den är bevakningen bara en
        // bevakning igen, och dedupen per sälj slutar fungera efter en återställning.
        val restored = BackupSerializer.decode(BackupSerializer.encode(fullPayload())).getOrThrow()

        assertEquals(listOf(8L, null), restored.switchWatches.map { it.sellTransactionId })
        assertEquals(listOf(null, 7L), restored.switchWatches.map { it.buyTransactionId })
        // Länkarna pekar på transaktioner som finns i samma fil.
        val transactionIds = restored.transactions.map { it.id }.toSet()
        assertTrue(restored.switchWatches.mapNotNull { it.sellTransactionId }.all { it in transactionIds })
        assertTrue(restored.switchWatches.mapNotNull { it.buyTransactionId }.all { it in transactionIds })
    }

    @Test
    fun `en fil utan transaktionslankar lases som okopplade bevakningar`() {
        // Filer skrivna före 0.67.0 saknar nycklarna helt: samma formatversion, fälten läses som
        // null — aldrig ett påhittat id, aldrig en avvisad fil.
        val utanLankar = BackupSerializer.encode(fullPayload())
            .replace(Regex(",\\s*\"sellTransactionId\": (\\d+|null)"), "")
            .replace(Regex(",\\s*\"buyTransactionId\": (\\d+|null)"), "")
        assertFalse(utanLankar.contains("TransactionId"))

        val restored = BackupSerializer.decode(utanLankar).getOrThrow()

        assertEquals(2, restored.switchWatches.size)
        assertTrue(restored.switchWatches.all { it.sellTransactionId == null && it.buyTransactionId == null })
        assertEquals(BackupPayload.FORMAT_VERSION, restored.formatVersion)
    }

    @Test
    fun `malnivan och indexfiltret overlever och en aldre fil utan filtret lases som alla fonder`() {
        // Issue #150: nivån och filtret är användarens val på skärmen. En fil från före 0.71.0 saknar
        // nyckeln — samma formatversion, filtret falskt, aldrig en avvisad fil.
        val restored = BackupSerializer.decode(BackupSerializer.encode(fullPayload())).getOrThrow()
        assertEquals(listOf(true, false), restored.switchWatches.map { it.indexOnly })
        assertEquals(listOf(4, null), restored.switchWatches.map { it.targetLevel })

        val utanFilter = BackupSerializer.encode(fullPayload()).replace(Regex(",\\s*\"indexOnly\": (true|false)"), "")
        assertFalse(utanFilter.contains("indexOnly"))
        val old = BackupSerializer.decode(utanFilter).getOrThrow()
        assertTrue(old.switchWatches.none { it.indexOnly })
        assertEquals(listOf(4, null), old.switchWatches.map { it.targetLevel })
        assertEquals(BackupPayload.FORMAT_VERSION, old.formatVersion)
    }

    @Test
    fun `ett pagaende byte overlever med nollpunkt, kalla och avslut`() {
        // Nollpunkten (navAtStart) kan inte återskapas i efterhand: kandidaten ligger inte i
        // kurscachen (ANA-11), så tappas den går utvecklingen sedan säljdagen aldrig att visa igen.
        val restored = BackupSerializer.decode(BackupSerializer.encode(fullPayload())).getOrThrow()

        val open = restored.switchWatches.first { it.isOpen }
        assertEquals(listOf(95.25, null), open.candidates.map { it.navAtStart })
        assertEquals(listOf(19_900L, null), open.candidates.map { it.navAtStartEpochDay })
        assertEquals(
            listOf(SwitchWatchCandidateSource.AUTO, SwitchWatchCandidateSource.MANUELL),
            open.candidates.map { it.source },
        )
        assertEquals(listOf(0, 1), open.candidates.map { it.position })
        assertEquals(3L, open.sourceRecordId)

        val closed = restored.switchWatches.first { !it.isOpen }
        assertEquals("SE0001466368", closed.boughtIsin)
        assertEquals(SwitchWatchCloseReason.KOPT, closed.closeReason)
    }

    @Test
    fun `en fil utan switchWatches lases som inga pagaende byten`() {
        // Filer skrivna före issue #114 saknar nyckeln helt. Fältet är tillagt med default, så
        // FORMAT_VERSION höjs inte — en äldre fil ska läsas, inte avvisas.
        val root = Json.parseToJsonElement(BackupSerializer.encode(fullPayload())).jsonObject
        val utanBevakningar = JsonObject(root.filterKeys { it != "switchWatches" }).toString()

        val restored = BackupSerializer.decode(utanBevakningar).getOrThrow()

        assertTrue(restored.switchWatches.isEmpty())
        assertEquals(3, restored.suggestionRecords.size)
    }

    @Test
    fun `avstaenden overlever med par, sort och datum`() {
        // HEM-14: ett val som inte går att räkna fram — tappas det kommer varje avstått förslag
        // tillbaka efter en återställning.
        val restored = BackupSerializer.decode(BackupSerializer.encode(fullPayload())).getOrThrow()

        assertEquals(
            listOf(
                SuggestionDismissal("SE0000582033", "SE0001466368", SuggestionKind.RISK_PLAN, 19_810),
                SuggestionDismissal("SE0000582033", "SE0005991445", SuggestionKind.FEE, 19_420),
            ),
            restored.suggestionDismissals,
        )
    }

    @Test
    fun `en fil utan suggestionDismissals lases som inga avstaenden`() {
        // Filer skrivna före 0.63.0 saknar nyckeln. Tillagt fält med default — FORMAT_VERSION
        // höjs inte, och filen ska läsas, inte avvisas.
        val root = Json.parseToJsonElement(BackupSerializer.encode(fullPayload())).jsonObject
        val utanAvstaenden = JsonObject(root.filterKeys { it != "suggestionDismissals" }).toString()

        val restored = BackupSerializer.decode(utanAvstaenden).getOrThrow()

        assertTrue(restored.suggestionDismissals.isEmpty())
        assertEquals(3, restored.suggestionRecords.size)
        assertEquals(2, restored.switchWatches.size)
    }

    @Test
    fun `ett avstaende utan kind lases som bytesplansbyte`() {
        // Tolerant mot ett saknat fält inne i listan: defaulten är bytesplanen, som Beslut visar.
        val root = Json.parseToJsonElement(BackupSerializer.encode(fullPayload())).jsonObject
        val dismissals = root.getValue("suggestionDismissals").jsonArray
            .map { JsonObject(it.jsonObject.filterKeys { key -> key != "kind" }) }
        val utanKind = JsonObject(root + ("suggestionDismissals" to JsonArray(dismissals))).toString()

        val restored = BackupSerializer.decode(utanKind).getOrThrow()

        assertEquals(2, restored.suggestionDismissals.size)
        assertTrue(restored.suggestionDismissals.all { it.kind == SuggestionKind.RISK_PLAN })
    }

    @Test
    fun `nyare formatversion avvisas i stallet for att lasas delvis`() {
        val fromNewerApp = BackupSerializer.encode(fullPayload())
            .replace("\"formatVersion\": ${BackupPayload.FORMAT_VERSION}", "\"formatVersion\": ${BackupPayload.FORMAT_VERSION + 1}")

        val error = BackupSerializer.decode(fromNewerApp).exceptionOrNull()

        assertTrue(error is BackupFormatException)
        assertEquals(BackupFormatException.Reason.UNSUPPORTED_VERSION, (error as BackupFormatException).reason)
        assertEquals(BackupPayload.FORMAT_VERSION + 1, error.fileVersion)
    }

    @Test
    fun `okanda nycklar ignoreras sa en fil fran en nyare app med samma version gar att lasa`() {
        val withExtraField = BackupSerializer.encode(fullPayload())
            .replaceFirst("{", "{\n  \"nagotHelt Nytt\": 42,")

        val restored = BackupSerializer.decode(withExtraField).getOrThrow()

        assertEquals(fullPayload(), restored)
    }

    @Test
    fun `trunkerad fil ger fel utan payload`() {
        val truncated = BackupSerializer.encode(fullPayload()).take(120)

        val error = BackupSerializer.decode(truncated).exceptionOrNull()

        assertEquals(BackupFormatException.Reason.UNREADABLE, (error as BackupFormatException).reason)
    }

    @Test
    fun `helt annan json ger fel i stallet for en tom payload`() {
        // Utan versionskontrollen hade en godtycklig JSON-fil avkodats till en payload med bara
        // defaultvärden — alltså en "lyckad" återställning som tömmer all data.
        val error = BackupSerializer.decode("""{"nagot": "annat"}""").exceptionOrNull()

        assertEquals(BackupFormatException.Reason.UNREADABLE, (error as BackupFormatException).reason)
    }

    @Test
    fun `skrap som inte ens ar json ger fel`() {
        val error = BackupSerializer.decode("inte json alls").exceptionOrNull()

        assertEquals(BackupFormatException.Reason.UNREADABLE, (error as BackupFormatException).reason)
    }

    @Test
    fun `tom fil ger fel`() {
        val error = BackupSerializer.decode("").exceptionOrNull()

        assertEquals(BackupFormatException.Reason.UNREADABLE, (error as BackupFormatException).reason)
    }
}
