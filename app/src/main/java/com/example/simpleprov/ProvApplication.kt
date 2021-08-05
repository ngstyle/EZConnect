package com.example.simpleprov

import android.app.Application
import timber.log.Timber

/**
 * Created by chon on 2021/8/3.
 * Hedge.
 */
class ProvApplication: Application() {

    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
    }
}