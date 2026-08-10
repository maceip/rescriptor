package wasmo.jobqueue

import kotlin.time.Instant
import okio.ByteString
import wasmo.jobs.JobQueue

/** Records what an app enqueued instead of running it, so a test can assert on it or run it later. */
class FakeJobQueueFactory : JobQueue.Factory {
  private val queues = linkedMapOf<String, FakeJobQueue>()

  /** Every job still enqueued, oldest first, across all queues. */
  val enqueued: List<EnqueuedJob>
    get() = queues.values.flatMap { it.jobs }

  suspend fun awaitIdle() {
  }

  override fun get(name: String): FakeJobQueue = queues.getOrPut(name) { FakeJobQueue(name) }

  data class EnqueuedJob(
    val queueName: String,
    val job: ByteString,
    val executeAt: Instant?,
  )

  class FakeJobQueue internal constructor(
    private val queueName: String,
  ) : JobQueue {
    internal val jobs = mutableListOf<EnqueuedJob>()

    /** Enqueuing a job that is already enqueued replaces it, as the real queues do. */
    override suspend fun enqueue(job: ByteString, executeAt: Instant?) {
      jobs.removeAll { it.job == job }
      jobs += EnqueuedJob(queueName = queueName, job = job, executeAt = executeAt)
    }

    override suspend fun cancel(job: ByteString) {
      jobs.removeAll { it.job == job }
    }
  }
}
