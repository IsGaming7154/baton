package dev.baton.pipeline.live

import dev.baton.pipeline.ServiceRefused
import software.amazon.awssdk.core.exception.SdkServiceException

/**
 * Make one billed request, and turn the service rejecting it into [ServiceRefused], so the
 * [dev.baton.pipeline.SpendGuard] settles that call to $0. Only a 4xx that says the request never
 * ran counts. Some 4xx do not say that, and keep their estimate:
 *
 * - 408 and 424, Bedrock's `ModelTimeoutException` and `ModelErrorException`, are raised while
 *   the model is processing (aws-sdk-java-v2 2.54.19, bedrockruntime service-2.json), so may be billed.
 * - Throttling, 429 or Polly's 400 `ThrottlingException`: almost certainly unbilled, but nothing
 *   on record says so.
 *
 * A 5xx, an `SdkClientException` (timeouts, IO) or anything that is not a service response
 * passes through as it came.
 */
internal inline fun <T> refusalsTranslated(what: String, request: () -> T): T = try {
    request()
} catch (e: SdkServiceException) {
    val status = e.statusCode()
    if (status in 400..499 && status != 408 && status != 424 && !e.isThrottlingException) {
        throw ServiceRefused("$what was refused by the service, so not billed: ${e.message}", e)
    }
    throw e
}
