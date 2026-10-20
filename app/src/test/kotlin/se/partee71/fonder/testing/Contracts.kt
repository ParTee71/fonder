package se.partee71.fonder.testing

import se.partee71.fonder.data.common.AppErrors
import se.partee71.fonder.data.common.DataError
import se.partee71.fonder.ui.theme.FonderTheme
import se.partee71.ui.testing.ContractEnvironment

/**
 * Det ramarnas kontraktstester (android-ui `ui-testing`: `runListScreenContract`,
 * `runEditScreenContract`, `runDetailScreenContract`) behöver av Fonder: temat, felöversättningen och
 * ett fel där "Försök igen" hjälper – ett nätverks-/I/O-fel (NFR-2, NFR-6). En gång för alla skärmtester
 * (skill testing-strategy → DRY i tester).
 */
val contracts = ContractEnvironment(AppErrors, DataError.Network) { FonderTheme(content = it) }
