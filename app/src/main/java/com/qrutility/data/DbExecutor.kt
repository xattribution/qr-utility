package com.qrutility.data

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Runs blocking database/network work off the main thread and delivers the
 * result back on the main thread. Deliberately tiny so the app keeps no
 * coroutine dependency.
 */
object DbExecutor {
    private val pool = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())

    /**
     * @param work blocking operation, run on a background thread
     * @param onDone called on the main thread with either the result or the thrown error
     */
    fun <T> run(work: () -> T, onDone: (Result<T>) -> Unit) {
        pool.execute {
            val result = runCatching(work)
            main.post { onDone(result) }
        }
    }
}
