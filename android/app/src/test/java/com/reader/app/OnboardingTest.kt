package com.reader.app

import com.reader.app.ui.Onboarding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingTest {
  @Test
  fun onboardingOnlyAppearsOnAnEmptyUndismissedLibrary() {
    assertTrue(Onboarding.shouldShow(dismissed = false, documentCount = 0))
    assertFalse(Onboarding.shouldShow(dismissed = true, documentCount = 0))
    assertFalse(Onboarding.shouldShow(dismissed = false, documentCount = 1))
    assertFalse(Onboarding.shouldShow(dismissed = true, documentCount = 25))
  }

  @Test
  fun onboardingExplainsTheThreeProductPieces() {
    assertEquals(3, Onboarding.steps.size)
    assertTrue(Onboarding.steps.first().contains("Chrome"))
    assertTrue(Onboarding.steps.joinToString(" ").contains("Pair"))
    assertTrue(Onboarding.steps.joinToString(" ").contains("encrypted"))
  }
}
