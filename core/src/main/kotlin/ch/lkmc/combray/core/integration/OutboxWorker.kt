package ch.lkmc.combray.core.integration

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Implemented by the Application: gives the worker the process's integration objects. */
interface OutboxHost {
    val outboxDispatcher: OutboxDispatcher
}

/** Background wakeup only: the database decides what is due. */
class OutboxWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val host = applicationContext as? OutboxHost ?: return Result.failure()
        host.outboxDispatcher.drain()
        return Result.success()
    }
}

/**
 * One pending wakeup at the next due time, with network. Doze may delay it; the queue's state
 * stays correct whenever it runs.
 */
class WorkManagerScheduler(private val context: Context, private val clock: () -> Long = System::currentTimeMillis) : WakeupScheduler {
    override fun schedule(atMillis: Long?) {
        val workManager = WorkManager.getInstance(context)
        if (atMillis == null) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = OneTimeWorkRequestBuilder<OutboxWorker>()
            .setInitialDelay((atMillis - clock()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    private companion object {
        const val WORK_NAME = "combray-outbox"
    }
}
