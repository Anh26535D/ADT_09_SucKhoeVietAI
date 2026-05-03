package edu.hust.medicalaichatbot

import android.app.Application

class MedicalAIChatbotApplication: Application() {
    lateinit var appRepositoryContainer: AppRepositoryContainer

    override fun onCreate() {
        super.onCreate()
        appRepositoryContainer = AppRepositoryContainer(this)
    }
}