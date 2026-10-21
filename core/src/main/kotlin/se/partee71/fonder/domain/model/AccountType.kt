package se.partee71.fonder.domain.model

import kotlinx.serialization.Serializable

/**
 * Kontotypen fondinnehaven ligger i (SET-4, issue #70) — avgör om ett fondbyte kostar något.
 * I [ISK_KF] finns ingen realisationsskatt och ett byte kostar i praktiken ingenting; i
 * [DEPA_AF] utlöser ett byte 30 % skatt på vinsten, vilket kräver en meravkastning ingen
 * signal appen har kan motivera (se [se.partee71.fonder.domain.usecase.SwitchPlanCalc]).
 * Genuin användarinput — appen gissar aldrig kontotyp, se [se.partee71.fonder.domain.model.RiskProfile]
 * för samma princip.
 */
@Serializable
enum class AccountType { ISK_KF, DEPA_AF }
