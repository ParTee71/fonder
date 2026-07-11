package se.partee71.fonder

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dagger.hilt.android.HiltAndroidApp
import se.partee71.fonder.worker.FundPriceRefreshScheduler
import javax.inject.Inject

@HiltAndroidApp
class FonderApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var fundPriceRefreshScheduler: FundPriceRefreshScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Krävs av PdfBox-Android (se PdfBoxTextExtractor, import av avräkningsnotor) innan
        // några PDF-anrop görs.
        PDFBoxResourceLoader.init(applicationContext)
        // Handelsdagsmedveten kursuppdatering (issue #27, TP-17/TP-5) — launch-gate vid
        // varje appstart plus en gles periodisk backstop, båda billiga tack vare
        // FundPriceUpdateWorker.refreshAll:s egen staleness-gate.
        fundPriceRefreshScheduler.scheduleOnLaunch()
        fundPriceRefreshScheduler.scheduleBackstop()
    }
}
