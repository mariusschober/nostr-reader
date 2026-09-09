package com.reader.app.capture

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.reader.app.data.ReaderDb
import java.util.concurrent.TimeUnit

/**
 * Bounded capture worker. Receives only a request ID (never article payloads)
 * and drives one durable request: fetch -> extract -> crash-safe commit.
 *
 * - Unique work per request ID (`capture/<id>`, KEEP) provides
 *   deduplication: repeated intents for the same active URL resolve to the
 *   same request ID upstream and never enqueue a second chain.
 * - Requires connectivity; offline shares stay durable and run when the OS
 *   provides a network (WorkManager persists across restart).
 * - Temporary errors return retry with persisted attempts/nextAttemptAt;
 *   permanent/extraction failures commit an honest link-only fallback.
 * - A cancelled request (generation bump) never commits from a stale worker:
 *   completion transactions check generation + state.
 */
class CaptureWorker(
  context: Context,
  params: WorkerParameters,
) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    val requestId = inputData.getString(KEY_REQUEST_ID) ?: return Result.success()
    val db = ReaderDb.get(applicationContext)
    val repo = CaptureRepository(db)
    // One-clock retry: the repo's nextAttemptAt gates fetching. A premature
    // WorkManager wakeup burns no attempt — it just asks to be retried, and
    // the exponential backoffs converge on the repo schedule.
    if (db.captureRequests().dueById(requestId, System.currentTimeMillis()) == null) {
      return if (db.captureRequests().byId(requestId) == null) Result.success() else Result.retry()
    }
    val active = repo.markFetching(requestId) ?: run {
      // Terminal/cancelled/missing: stale or duplicate work, no-op success.
      return Result.success()
    }
    val generation = active.generation
    val fetch = CaptureFetcher()
    return try {
      val page = try {
        fetch.fetch(active.originalUrl)
      } catch (e: CaptureException) {
        return onFetchFailure(repo, active.requestId, e)
      } catch (e: java.io.InterruptedIOException) {
        return onFetchFailure(repo, active.requestId, CaptureException("timeout_retryable", "Fetching timed out; Reader will retry", retryable = true))
      } catch (e: java.net.UnknownHostException) {
        return onFetchFailure(repo, active.requestId, CaptureException("dns_retryable", "You're offline or the host is unknown; Reader will retry", retryable = true))
      } catch (e: java.io.IOException) {
        return onFetchFailure(repo, active.requestId, CaptureException("network_retryable", "Network error; Reader will retry", retryable = true))
      }
      val extracted = try {
        ArticleExtractor.extract(page.htmlBytes, page.finalUrl)
      } catch (e: ArticleExtractor.ExtractionFailed) {
        repo.completeLinkOnly(
          id = active.requestId,
          generation = generation,
          resolvedUrl = page.finalUrl,
          title = runCatching { ArticleExtractor.extractTitle(org.jsoup.Jsoup.parse(String(page.htmlBytes, Charsets.UTF_8), page.finalUrl), page.finalUrl) }.getOrNull(),
          reason = e.message?.substringAfter(": ") ?: "No confident article text was found",
          errorCode = e.errorCode,
        )
        Log.i(TAG, "capture=${active.requestId.take(8)} link_only=${e.errorCode}")
        return Result.success()
      }
      val committed = repo.completeWithArticle(active.requestId, generation, extracted, page.finalUrl)
      if (committed == null) {
        Log.w(TAG, "capture=${active.requestId.take(8)} stale_commit_suppressed")
      } else {
        Log.i(TAG, "capture=${active.requestId.take(8)} completed doc=${committed.take(8)}")
      }
      Result.success()
    } catch (e: kotlinx.coroutines.CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "capture=${requestId.take(8)} worker_error=${e.message}")
      // Unknown failure: retry while attempts remain, else honest link.
      onFetchFailure(repo, requestId, CaptureException("worker_error", "Capture hit an unexpected error", retryable = true))
    }
  }

  private suspend fun onFetchFailure(repo: CaptureRepository, requestId: String, e: CaptureException): Result {
    val human = humanMessage(e)
    if (!e.retryable) {
      val current = ReaderDb.get(applicationContext).captureRequests().byId(requestId)
      if (current != null) {
        repo.completeLinkOnly(requestId, current.generation, current.resolvedUrl, current.subjectTitle, human, e.code)
      }
      return Result.success()
    }
    val after = repo.markRetryable(requestId, e.code, human)
    // markRetryable may have exhausted attempts and already committed a link.
    if (after?.state == "link_only") return Result.success()
    return Result.retry()
  }

  companion object {
    const val KEY_REQUEST_ID = "requestId"
    private const val TAG = "ReaderCapture"

    private fun connectedConstraints(): Constraints =
      Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun scheduleById(context: Context, requestId: String) {
      val req = OneTimeWorkRequestBuilder<CaptureWorker>()
        .setInputData(workDataOf(KEY_REQUEST_ID to requestId))
        .setConstraints(connectedConstraints())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .addTag("reader-capture")
        .build()
      WorkManager.getInstance(context).enqueueUniqueWork(
        "capture/$requestId", ExistingWorkPolicy.KEEP, req,
      )
    }

    /** Reschedule durable pending work after restart/upgrade. Safe to call often. */
    suspend fun rescheduleActive(context: Context, pageSize: Int = 50, maxTotal: Int = 500) {
      val db = ReaderDb.get(context)
      var scheduled = 0
      try {
        // Stale fetching rows first (their worker died with the process;
        // a live worker dedups via KEEP, so this is safe unconditionally).
        var offset = 0
        while (scheduled < maxTotal) {
          val rows = db.captureRequests().fetchingPaged(pageSize, offset)
          if (rows.isEmpty()) break
          rows.forEach { scheduleById(context, it.requestId) }
          scheduled += rows.size
          offset += rows.size
          if (rows.size < pageSize) break
        }
        // Then every due pending row — no 20-row starvation cap.
        offset = 0
        while (scheduled < maxTotal) {
          val rows = db.captureRequests().duePaged(System.currentTimeMillis(), pageSize, offset)
          if (rows.isEmpty()) break
          rows.forEach { scheduleById(context, it.requestId) }
          scheduled += rows.size
          offset += rows.size
          if (rows.size < pageSize) break
        }
      } catch (_: Exception) { /* best-effort cold-start sweep */ }
    }

    internal fun humanMessage(e: CaptureException): String = when (e.code) {
      "access_denied" -> "This page needs a login or subscription; Reader does not bypass access controls. The link is kept — open the original instead."
      "not_found" -> "This page was not found (404). The link is kept."
      "unsupported_mime" -> e.message?.substringAfter(": ") ?: "This link is not an article page. The link is kept."
      "too_many_redirects", "bad_redirect" -> "This page has a broken redirect chain. The link is kept — try opening the original."
      "prohibited_destination" -> "This link points somewhere Reader will not fetch (private/local address or insecure redirect). The link is kept."
      "body_too_large" -> "This page is too large to capture. The link is kept — open the original instead."
      "empty_page" -> "This page has no readable content. The link is kept."
      "timeout_retryable", "dns_retryable", "network_retryable", "rate_limited_retryable", "server_error_retryable" ->
        "You're offline or the site is unavailable; Reader will retry automatically."
      else -> e.message?.substringAfter(": ") ?: "Capture failed; the link is kept."
    }
  }
}
