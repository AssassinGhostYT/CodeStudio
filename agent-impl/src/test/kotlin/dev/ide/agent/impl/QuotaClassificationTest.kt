package dev.ide.agent.impl

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A Gemini `RESOURCE_EXHAUSTED` answers three different situations with the same 429 and almost the same
 * wording: a model with no free quota at all, a spent daily allowance, and a per-minute burst. Only the first
 * two need a different model and only the third clears by waiting, so they have to be told apart.
 */
class QuotaClassificationTest {
    private fun geminiQuotaBody(quotaId: String, value: String?, model: String = "gemini-2.5-pro"): String {
        val quotaValue = value?.let { ""","quotaValue":"$it"""" }.orEmpty()
        return """
            {"error":{"code":429,"status":"RESOURCE_EXHAUSTED",
             "message":"You exceeded your current quota, please check your plan and billing details. * Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: ${value ?: "10"}, model: $model\nPlease retry in 39s.",
             "details":[
               {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[
                 {"quotaMetric":"generativelanguage.googleapis.com/generate_content_free_tier_requests",
                  "quotaId":"$quotaId","quotaDimensions":{"location":"global","model":"$model"$quotaValue}]},
               {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"39s"}]}}
        """.trimIndent()
    }

    @Test
    fun aModelWithNoFreeQuotaIsNotMistakenForARateLimit() {
        val parsed = LlmErrors.parseHttp(429, geminiQuotaBody("GenerateRequestsPerDayPerProjectPerModel-FreeTier", "0"), null)
        assertEquals(LlmErrorKind.MODEL_NOT_ON_PLAN, parsed.kind)
        assertFalse(parsed.retryable)
        assertTrue(parsed.message.contains("'gemini-2.5-pro' has no quota"), parsed.message)
    }

    @Test
    fun aSpentDailyAllowanceIsReportedAsDaily() {
        val parsed = LlmErrors.parseHttp(
            429, geminiQuotaBody("GenerateRequestsPerDayPerProjectPerModel-FreeTier", "20", "gemini-3.8-flash"), null,
        )
        assertEquals(LlmErrorKind.DAILY_LIMIT, parsed.kind)
        assertFalse(parsed.retryable)
        assertTrue(parsed.message.contains("20 per day"), parsed.message)
    }

    @Test
    fun aPerMinuteLimitStaysRetryableAndCarriesItsCeiling() {
        val parsed = LlmErrors.parseHttp(
            429, geminiQuotaBody("GenerateRequestsPerMinutePerProjectPerModel-FreeTier", "10", "gemini-3.8-flash"), null,
        )
        assertEquals(LlmErrorKind.RATE_LIMIT, parsed.kind)
        assertTrue(parsed.retryable)
        assertEquals(39_000L, parsed.retryAfterMs)
        assertEquals(QuotaInfo.Window.MINUTE, parsed.quota?.window)
    }

    @Test
    fun aTokenLimitIsToldApartFromARequestLimit() {
        val parsed = LlmErrors.parseHttp(
            429, geminiQuotaBody("GenerateTokensPerMinutePerProjectPerModel-FreeTier", "1000", "gemini-3.8-flash"), null,
        )
        assertEquals(LlmErrorKind.RATE_LIMIT, parsed.kind)
        assertEquals(QuotaInfo.Metric.INPUT_TOKENS, parsed.quota?.metric)
        assertTrue(parsed.message.contains("tokens per minute"), parsed.message)
    }

    /** With no quota detail in the body, the wording alone still has to name the model and its zero limit. */
    @Test
    fun theWordingAloneStillIdentifiesAModelWithNoQuota() {
        val body = """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED",
            "message":"Quota exceeded for metric: generate_content_free_tier_requests, limit: 0, model: gemini-2.5-pro"}}"""
        val parsed = LlmErrors.parseHttp(429, body, null)
        assertEquals(LlmErrorKind.MODEL_NOT_ON_PLAN, parsed.kind)
        assertEquals("gemini-2.5-pro", parsed.quota?.model)
    }

    @Test
    fun anErrorWithoutAnyQuotaSaysNothingAboutOne() {
        val body = """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"Resource has been exhausted"}}"""
        val parsed = LlmErrors.parseHttp(429, body, null)
        assertEquals(LlmErrorKind.RATE_LIMIT, parsed.kind)
        assertNull(parsed.quota)
    }

    /** A spent OpenAI balance is billing exhaustion, not something to wait out. */
    @Test
    fun billingExhaustionIsNotARateLimit() {
        val body = """{"error":{"type":"insufficient_quota","message":"You exceeded your current quota"}}"""
        val parsed = LlmErrors.parseHttp(429, body, null)
        assertEquals(LlmErrorKind.QUOTA, parsed.kind)
        assertFalse(parsed.retryable)
    }

    @Test
    fun theHttpExceptionCarriesTheKindSoTheHostCanOfferAFix() {
        val parsed = LlmErrors.parseHttp(429, geminiQuotaBody("GenerateRequestsPerDayPerProjectPerModel-FreeTier", "0"), null)
        val failure = LlmHttpException(
            parsed.message, 429, parsed.retryAfterMs, parsed.retryable, parsed.kind, parsed.quota,
        )
        assertEquals(LlmErrorKind.MODEL_NOT_ON_PLAN, failure.kind)
        assertEquals(0L, failure.quota?.limit)
    }
}
