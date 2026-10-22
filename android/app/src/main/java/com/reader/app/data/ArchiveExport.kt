package com.reader.app.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Naming for the user-visible archive export (SAF destination, never app-private storage). */
object ArchiveExport {
  fun filename(now: Long): String {
    val format = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply {
      timeZone = TimeZone.getTimeZone("UTC")
    }
    return "reader-archive-${format.format(Date(now))}.zip"
  }
}
