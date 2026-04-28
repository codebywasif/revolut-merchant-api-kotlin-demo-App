package com.example.revolutdemo

import android.app.Application
import com.revolut.payments.RevolutPaymentsSDK

/**
 * Initialises the Revolut Merchant Card Form SDK once at process start.
 */
class RevolutDemoApp : Application() {

    override fun onCreate() {
        super.onCreate()

        RevolutPaymentsSDK.configure(
            RevolutPaymentsSDK.Configuration(
                environment = RevolutPaymentsSDK.Environment.SANDBOX,
                merchantPublicKey = BuildConfig.REVOLUT_PUBLIC_KEY,
            ),
        )
    }
}
