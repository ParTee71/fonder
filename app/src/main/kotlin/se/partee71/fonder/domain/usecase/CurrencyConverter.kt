package se.partee71.fonder.domain.usecase

import se.partee71.fonder.domain.model.FundPrice

/**
 * Räknar om fondkurser i främmande valuta till kronor (se KRAVLISTA TP-19/TP-20).
 *
 * Ren och testbar: växelkurserna kommer in som en färdig tabell, hämtning och cachning sköts
 * av repository-lagret.
 *
 * Bakgrund: fondlista noterar varje fond i **fondens egen valuta** medan appens hela
 * värdekedja räknar i kronor. Utan konvertering tolkades en USD-kurs tyst som kronor och gav
 * ett värde fel med hela växelkursen (issue #41).
 */
object CurrencyConverter {

    /**
     * Hur många dagar bakåt en växelkurs får återanvändas när den efterfrågade dagen saknas.
     *
     * Valutakurser noteras bara bankdagar, så en fondkurs daterad en helgdag (eller en dag då
     * valutamarknaden var stängd men fonden ändå satte NAV) saknar egen notering. Då används
     * närmast föregående. Marginalen täcker en lång helg — bortom det är kursen för gammal
     * för att vara ärlig, och kursen hoppas hellre över.
     */
    internal const val MAX_RATE_AGE_DAYS = 7L

    /**
     * [prices] omräknade till kronor med [ratesByEpochDay] (`1 enhet = x SEK`).
     *
     * Kurser som redan är i [FundPrice.VALUE_CURRENCY] passerar oförändrade. En kurs vars dag
     * saknar växelkurs — och inte har någon inom [MAX_RATE_AGE_DAYS] bakåt — **utelämnas**:
     * hellre en lucka i historiken än ett belopp räknat på en gissad kurs (samma princip som
     * POR-3, aldrig ett felaktigt värde).
     */
    fun toValueCurrency(prices: List<FundPrice>, ratesByEpochDay: Map<Long, Double>): List<FundPrice> =
        prices.mapNotNull { price ->
            if (price.currency.equals(FundPrice.VALUE_CURRENCY, ignoreCase = true)) return@mapNotNull price
            val rate = rateFor(price.epochDay, ratesByEpochDay) ?: return@mapNotNull null
            price.copy(nav = price.nav * rate, currency = FundPrice.VALUE_CURRENCY)
        }

    /** Kursen för [epochDay], eller närmast föregående inom [MAX_RATE_AGE_DAYS]. Null om ingen finns. */
    internal fun rateFor(epochDay: Long, ratesByEpochDay: Map<Long, Double>): Double? {
        for (offset in 0..MAX_RATE_AGE_DAYS) {
            ratesByEpochDay[epochDay - offset]?.let { return it }
        }
        return null
    }
}
