package app.emberio

import android.app.Application

class EmberioApp : Application() {
    lateinit var repository: MugRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = MugRepository(this)
        repository.notifier.createChannels()
    }
}
