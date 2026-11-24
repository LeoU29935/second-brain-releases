package com.leo.secondbrain.data

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.leo.secondbrain.BrainApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class TrashCleanup : JobService() {
    private var task: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        task = CoroutineScope(Dispatchers.Main).launch {
            val retry = try {
                (application as BrainApplication).repository.purgeExpired()
                false
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("SecondBrain", "Could not purge expired deleted items", error)
                true
            }
            task = null
            jobFinished(params, retry)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        task?.cancel()
        task = null
        return true
    }

    companion object {
        private const val JOB_ID = 31401
        private const val DAY_MS = 24L * 60 * 60 * 1000

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, TrashCleanup::class.java))
                .setPeriodic(DAY_MS)
                .setPersisted(true)
                .build()
            if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
                Log.w("SecondBrain", "Could not schedule background cleanup")
            }
        }
    }
}
