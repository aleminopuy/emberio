package app.emberio.pebble

import app.emberio.EmberioApp
import app.emberio.service.MugService
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import java.util.UUID

/**
 * The Pebble app binds to this (PebbleKit 2) while the Emberio watchapp is open, which also
 * wakes this app up if Android had stopped it.
 */
class WatchListenerService : BasePebbleListenerService() {
    private val repository get() = (application as EmberioApp).repository

    override suspend fun onMessageReceived(
        watchappUUID: UUID,
        data: PebbleDictionary,
        watch: WatchIdentifier,
    ): ReceiveResult {
        if (watchappUUID != WatchProtocol.WATCHAPP_UUID) return ReceiveResult.Nack
        MugService.startIfReady(this)
        return repository.bridge.onMessage(data, watch)
    }

    override fun onAppOpened(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID != WatchProtocol.WATCHAPP_UUID) return
        MugService.startIfReady(this)
        repository.bridge.onAppOpened(watch)
    }

    override fun onAppClosed(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID != WatchProtocol.WATCHAPP_UUID) return
        repository.bridge.onAppClosed(watch)
    }
}
