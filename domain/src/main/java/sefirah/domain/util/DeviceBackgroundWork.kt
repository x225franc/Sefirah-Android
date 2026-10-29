package sefirah.domain.util

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Runs slow device queries outside session locks, without concurrent duplicate loads. */
class DeviceBackgroundWork(private val scope: CoroutineScope) {
    private val jobs = ConcurrentHashMap<String, Job>()
    private val queryMutex = Mutex()

    @Synchronized
    fun start(deviceId: String, action: suspend () -> Unit) {
        if (jobs[deviceId]?.isActive == true) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                queryMutex.withLock { action() }
            } finally {
                jobs.remove(deviceId, currentCoroutineContext()[Job])
            }
        }
        jobs[deviceId] = job
        job.start()
    }

    @Synchronized
    fun cancel(deviceId: String) {
        jobs.remove(deviceId)?.cancel()
    }

    @Synchronized
    fun cancelAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }
}
