package fr.thesdupalais

import android.app.Application

class ThesDuPalaisApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
