package com.yinxin.uavfir.api

/**
 * Runs a list of [CommandPoller]s in sequence on each tick. A failure in one
 * poller does not abort the others — every poll attempt is independently
 * try/caught. The [onFailure] callback is invoked per-poller-error so the
 * host can route to whatever logging facility it uses.
 */
class CompositeCommandPoller(
    private val pollers: List<CommandPoller>,
    private val onFailure: (CommandPoller, Throwable) -> Unit = { _, _ -> },
) : CommandPoller {

    override suspend fun pollOnce(droneSn: String) {
        for (poller in pollers) {
            runCatching { poller.pollOnce(droneSn) }
                .onFailure { onFailure(poller, it) }
        }
    }
}
