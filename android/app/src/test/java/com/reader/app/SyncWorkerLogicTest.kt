package com.reader.app

import com.reader.app.sync.markAckForThisRun
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncWorkerLogicTest {
  @Test
  fun freshCrossRelayWrappersProduceOneAckBatchPerTransferPerRun() {
    val ackedTransfers = mutableSetOf<String>()
    assertTrue(markAckForThisRun(ackedTransfers, "transfer-a"))
    assertFalse(markAckForThisRun(ackedTransfers, "transfer-a"))
    assertTrue(markAckForThisRun(ackedTransfers, "transfer-b"))
  }
}
