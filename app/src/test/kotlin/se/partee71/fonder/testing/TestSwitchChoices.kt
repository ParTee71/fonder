package se.partee71.fonder.testing

import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.repository.FakeCachedPriceRepository
import se.partee71.fonder.data.repository.FakeFundMetadataRepository
import se.partee71.fonder.data.repository.FakeSuggestionDismissalRepository
import se.partee71.fonder.data.repository.FakeSuggestionRecordRepository
import se.partee71.fonder.data.repository.FakeSwitchWatchRepository
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.domain.usecase.DismissedPlanSource
import se.partee71.fonder.domain.usecase.InProgressSwitches
import se.partee71.fonder.domain.usecase.PendingDecisionsSource
import se.partee71.fonder.domain.usecase.SwitchChoicesSource

/**
 * En riktig [PendingDecisionsSource] på fakes — samma uppställning som Beslut läser, delad så att
 * varje test inte bygger kedjan själv (skill testing-strategy → DRY i tester).
 */
fun testPendingDecisionsSource(
    preferences: PreferencesRepository,
    suggestionRepo: FakeSuggestionRecordRepository = FakeSuggestionRecordRepository(),
    switchWatchRepo: FakeSwitchWatchRepository = FakeSwitchWatchRepository(),
    priceRepo: FundPriceRepository = FakeCachedPriceRepository(),
    metadataRepo: FakeFundMetadataRepository = FakeFundMetadataRepository(),
    dismissalRepo: FakeSuggestionDismissalRepository = FakeSuggestionDismissalRepository()
): PendingDecisionsSource = PendingDecisionsSource(
    preferences,
    suggestionRepo,
    switchWatchRepo,
    priceRepo,
    metadataRepo,
    DismissedPlanSource(suggestionRepo, dismissalRepo, preferences, metadataRepo),
    InProgressSwitches(switchWatchRepo, suggestionRepo)
)

/** Bytesväljarens indata (BYT-2) ur samma fakes — formulärets och PDF-importens tester. */
fun testSwitchChoicesSource(
    preferences: PreferencesRepository,
    suggestionRepo: FakeSuggestionRecordRepository = FakeSuggestionRecordRepository(),
    switchWatchRepo: FakeSwitchWatchRepository = FakeSwitchWatchRepository(),
    priceRepo: FundPriceRepository = FakeCachedPriceRepository(),
    metadataRepo: FakeFundMetadataRepository = FakeFundMetadataRepository(),
    dismissalRepo: FakeSuggestionDismissalRepository = FakeSuggestionDismissalRepository()
): SwitchChoicesSource = SwitchChoicesSource(
    testPendingDecisionsSource(preferences, suggestionRepo, switchWatchRepo, priceRepo, metadataRepo, dismissalRepo),
    switchWatchRepo,
    suggestionRepo
)
