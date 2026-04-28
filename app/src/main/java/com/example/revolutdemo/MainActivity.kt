package com.example.revolutdemo

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.revolut.cardpayments.api.CardPaymentLauncher
import com.revolut.cardpayments.api.CardPaymentParams
import com.revolut.cardpayments.api.CardPaymentResult
import com.revolut.cardpayments.core.api.UserType
import kotlinx.coroutines.launch

/**
 * One-page demo of the full Revolut "save card on first payment, charge it
 * later" flow.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var emailField: EditText
    private lateinit var statusView: TextView
    private lateinit var firstPayBtn: Button
    private lateinit var savedPayBtn: Button
    private lateinit var resetBtn: Button

    private val cardLauncher = CardPaymentLauncher(this) { result ->
        onCardPaymentResult(result)
    }

    // Set right before launching the SDK so onCardPaymentResult can fetch the
    // order back from Revolut and inspect what really happened (saved card id,
    // payments array, etc.) without depending on customer-level endpoints.
    private var pendingOrderId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("revolut_demo", Context.MODE_PRIVATE)

        emailField = findViewById(R.id.emailField)
        statusView = findViewById(R.id.statusView)
        firstPayBtn = findViewById(R.id.firstPayBtn)
        savedPayBtn = findViewById(R.id.savedPayBtn)
        resetBtn = findViewById(R.id.resetBtn)

        firstPayBtn.setOnClickListener { startFirstPayment() }
        savedPayBtn.setOnClickListener { startRepeatPayment() }
        resetBtn.setOnClickListener {
            prefs.edit().clear().apply()
            refreshUi()
            toast("Cleared local state")
        }

        refreshUi()
    }

    private fun refreshUi() {
        val customerId = prefs.getString(KEY_CUSTOMER_ID, null)
        val savedPmId = prefs.getString(KEY_SAVED_PM_ID, null)
        val savedLast4 = prefs.getString(KEY_SAVED_LAST4, null)

        val sb = StringBuilder()
        sb.append("Customer: ").append(customerId ?: "(none yet)").append('\n')
        if (savedPmId != null) {
            sb.append("Saved card: **** ").append(savedLast4 ?: "????").append('\n')
            sb.append("Payment method id: ").append(savedPmId)
        } else {
            sb.append("Saved card: (none yet)")
        }
        statusView.text = sb.toString()

        val haveCustomer = customerId != null
        val haveCard = savedPmId != null

        firstPayBtn.visibility = if (!haveCard) View.VISIBLE else View.GONE
        firstPayBtn.text = if (haveCustomer) "Pay £10 with new card" else "Create customer & pay £10"

        savedPayBtn.visibility = if (haveCard) View.VISIBLE else View.GONE
        savedPayBtn.text = "Pay £10 with saved card **** ${savedLast4 ?: "????"}"
    }

    private fun setBusy(busy: Boolean) {
        firstPayBtn.isEnabled = !busy
        savedPayBtn.isEnabled = !busy
        resetBtn.isEnabled = !busy
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun startFirstPayment() {
        val email = emailField.text.toString().trim()
        if (email.isEmpty() || !email.contains("@")) {
            toast("Enter a valid email")
            return
        }
        setBusy(true)
        lifecycleScope.launch {
            try {
                val customerId = prefs.getString(KEY_CUSTOMER_ID, null)
                    ?: RevolutApi.createCustomer(email).also {
                        prefs.edit().putString(KEY_CUSTOMER_ID, it).apply()
                    }

                val order = RevolutApi.createOrder(
                    amountMinor = 1000, // £10.00
                    currency = "GBP",
                    customerId = customerId,
                )

                pendingOrderId = order.id
                cardLauncher.launch(
                    CardPaymentParams(
                        orderId = order.token,
                        email = email,
                        savePaymentMethodFor = UserType.MERCHANT,
                    ),
                )
            } catch (e: Exception) {
                toast("Setup failed: ${e.message}")
                setBusy(false)
            }
        }
    }

    private fun onCardPaymentResult(result: CardPaymentResult) {
        when (result) {
            CardPaymentResult.Authorised -> {
                lifecycleScope.launch {
                    try {
                        val orderId = pendingOrderId
                            ?: error("pendingOrderId missing after a successful payment")

                        // Fetch the order itself: payments[].payment_method.id is
                        // populated by Revolut when save_payment_method_for took
                        // effect, which is the authoritative answer.
                        val order = RevolutApi.getOrderRaw(orderId)
                        android.util.Log.i("RevolutDemo", "Order after pay: $order")

                        val payments = order.optJSONArray("payments")
                        var savedPmId: String? = null
                        var savedLast4: String? = null
                        if (payments != null) {
                            for (i in 0 until payments.length()) {
                                val p = payments.getJSONObject(i)
                                val pm = p.optJSONObject("payment_method") ?: continue
                                val id = pm.optString("id").takeIf { it.isNotBlank() } ?: continue
                                savedPmId = id
                                val card = pm.optJSONObject("card")
                                savedLast4 = sequenceOf(
                                    card?.optString("last_four"),
                                    card?.optString("card_last_four"),
                                    card?.optString("last4"),
                                    pm.optString("card_last_four"),
                                    pm.optString("last_four"),
                                ).firstOrNull { !it.isNullOrBlank() }
                                break
                            }
                        }

                        if (savedPmId != null) {
                            prefs.edit()
                                .putString(KEY_SAVED_PM_ID, savedPmId)
                                .putString(KEY_SAVED_LAST4, savedLast4 ?: "????")
                                .apply()
                            toast("Paid £10 and saved card **** ${savedLast4 ?: "????"}")
                        } else {
                            // Dump the order so we can see why no payment_method id
                            // was attached (likely save_payment_method_for absent
                            // from the order, or unsupported in this API version).
                            toast("Paid, but order has no saved pm. Order: ${order.toString().take(400)}")
                        }
                    } catch (e: Exception) {
                        toast("Payment OK, but inspecting order failed: ${e.message}")
                    } finally {
                        pendingOrderId = null
                        refreshUi()
                        setBusy(false)
                    }
                }
            }
            is CardPaymentResult.Failed -> {
                toast("Failed: ${result.failureReason.name}")
                setBusy(false)
            }
            is CardPaymentResult.Declined -> {
                toast("Declined: ${result.failureReason.name}")
                setBusy(false)
            }
            CardPaymentResult.UserAbandonedPayment -> {
                toast("Cancelled")
                setBusy(false)
            }
            is CardPaymentResult.Error -> {
                toast("Error: $result")
                setBusy(false)
            }
        }
    }

    private fun startRepeatPayment() {
        val customerId = prefs.getString(KEY_CUSTOMER_ID, null) ?: return
        val pmId = prefs.getString(KEY_SAVED_PM_ID, null) ?: return

        setBusy(true)
        lifecycleScope.launch {
            try {
                val order = RevolutApi.createOrder(
                    amountMinor = 1000,
                    currency = "GBP",
                    customerId = customerId,
                )
                // /api/orders/{order_id}/payments addresses orders by their
                // internal id, not the public token used by the SDK.
                val resp = RevolutApi.payWithSavedCard(
                    orderId = order.id,
                    paymentMethodId = pmId,
                )
                val state = resp.optString("state", "(unknown)")
                toast("Repeat payment state: $state")
            } catch (e: Exception) {
                toast("Repeat payment failed: ${e.message}")
            } finally {
                setBusy(false)
            }
        }
    }

    private companion object {
        const val KEY_CUSTOMER_ID = "customer_id"
        const val KEY_SAVED_PM_ID = "saved_pm_id"
        const val KEY_SAVED_LAST4 = "saved_last4"
    }
}
