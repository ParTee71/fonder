package se.partee71.fonder

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dagger.hilt.android.HiltAndroidApp
import se.partee71.fonder.worker.FundPriceUpdateWorker
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class FonderApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Krävs av PdfBox-Android (se PdfBoxTextExtractor, import av avräkningsnotor) innan
        // några PDF-anrop görs.
        PDFBoxResourceLoader.init(applicationContext)
        scheduleDailyFundPriceUpdate()
    }

    private fun scheduleDailyFundPriceUpdate() {
        val request = PeriodicWorkRequestBuilder<FundPriceUpdateWorker>(24, TimeUnit.HOURS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "fonder_daily_price_update",
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
