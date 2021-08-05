package com.example.simpleprov

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import android.content.Context.MODE_PRIVATE

import android.content.SharedPreferences




/**
 * Created by chon on 2021/8/3.
 * Hedge.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {


    var sharedPreferences: SharedPreferences = getApplication<Application>().getSharedPreferences("sp", MODE_PRIVATE)

    fun rememberPass(ssid: String, pwd: String) {
        sharedPreferences.edit()
            .putString("${ssid}_$PASSWORD",pwd)
            .apply();
    }

    fun getPassBySsid(ssid: String?): String {
        if (ssid.isNullOrEmpty())
            return ""
        return sharedPreferences.getString("${ssid}_$PASSWORD", "")!!
    }

    fun validateCustomData(s: String): Boolean {
        return if (s.length % 2 == 1) {
            false
        } else s.matches(Regex("[0-9A-Fa-f]*"))
    }

    companion object {
        private const val PASSWORD = "password"
    }

}