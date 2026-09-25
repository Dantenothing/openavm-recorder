package com.dante.zeekrcapabilitylab.enhancement

import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

internal suspend fun awaitCapabilityResult(selected: CompletableFuture<String>): String = withTimeout(10_000) {
    // A blocked native query can live indefinitely. Do not attach whenComplete
    // closures retaining a cancelled dialog on every retry. This cancellable
    // delay yields; it never occupies a worker thread or cancels the shared query.
    while (!selected.isDone) delay(50)
    selected.join() // isDone above: cannot block waiting for native completion.
}
