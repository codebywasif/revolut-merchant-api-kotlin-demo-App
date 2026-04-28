package com.example.revolutdemo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Direct calls to the Revolut Merchant API.
 *
 * SECURITY:
 * In production every method below MUST live on YOUR backend. The Android app
 * should call YOUR endpoints (which hold the secret key) and never see sk_...
 * itself. This class embeds the secret only because the request was for a
 * single-page demo that runs end-to-end on a sandbox device.
 */
object RevolutApi {

    private const val BASE_URL = "https://sandbox-merchant.revolut.com"
    private const val API_VERSION = "2025-12-04"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json".toMediaType()

    private fun newRequest(path: String): Request.Builder =
        Request.Builder()
            .url("$BASE_URL$path")
            .header("Authorization", "Bearer ${BuildConfig.REVOLUT_SECRET_KEY}")
            .header("Revolut-Api-Version", API_VERSION)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")

    private suspend fun execute(req: Request): JSONObject = withContext(Dispatchers.IO) {
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code}: $body")
            }
            if (body.isBlank()) JSONObject() else JSONObject(body)
        }
    }

    private suspend fun executeArray(req: Request): JSONArray = withContext(Dispatchers.IO) {
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code}: $body")
            }
            if (body.isBlank()) JSONArray() else JSONArray(body)
        }
    }

    /** POST /api/1.0/customers — returns the new customer's id. */
    suspend fun createCustomer(email: String, fullName: String? = null): String {
        val payload = JSONObject().apply {
            put("email", email)
            if (!fullName.isNullOrBlank()) put("full_name", fullName)
        }
        val req = newRequest("/api/1.0/customers")
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()
        return execute(req).getString("id")
    }

    data class CreatedOrder(val id: String, val token: String)

    /**
     * POST /api/1.0/orders
     *
     * NOTE: linking a customer here only ASSOCIATES the customer with the
     * order. It does NOT save the payment method. The save flag goes on the
     * SDK call (CardPaymentParams.savePaymentMethodFor).
     */
    suspend fun createOrder(
        amountMinor: Int,
        currency: String,
        customerId: String,
    ): CreatedOrder {
        val payload = JSONObject().apply {
            put("amount", amountMinor)
            put("currency", currency)
            put("description", "Revolut sandbox demo order")
            // 2024+ API: customer is linked via top-level "customer_id".
            // The legacy nested {"customer": {"id": ...}} shape is silently
            // ignored under the new version, leaving the order with no
            // customer attached — which makes savePaymentMethodFor=MERCHANT
            // fail in the SDK with ApiError 1022.
            put("customer_id", customerId)
            // Tells the BACKEND to actually persist the card after the
            // payment succeeds. The SDK's savePaymentMethodFor flag only
            // controls consent/UI — without this field on the order, the
            // payment goes through but the card is never saved, and the
            // payment-methods endpoint returns [].
            put("save_payment_method_for", "merchant")
        }
        val req = newRequest("/api/1.0/orders")
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()
        val o = execute(req)
        // 2024+ API versions return the SDK-facing id as "public_id"; older
        // versions called it "token". Accept either so we don't depend on which
        // Revolut-Api-Version header the demo is pinned to.
        val publicId = o.optString("public_id").ifBlank { o.optString("token") }
        require(publicId.isNotBlank()) { "Order response missing public_id/token: $o" }
        return CreatedOrder(id = o.getString("id"), token = publicId)
    }

    data class SavedCard(
        val id: String,
        val lastFour: String,
        val brand: String?,
        val savedFor: String, // "customer" | "merchant"
    )

    /**
     * GET /api/1.0/orders/{order_id} — returns the raw JSON so callers can
     * inspect the payments[] array (which holds the saved card id) and any
     * other backend-side fields about save_payment_method_for processing.
     */
    suspend fun getOrderRaw(orderId: String): JSONObject {
        val req = newRequest("/api/1.0/orders/$orderId")
            .get()
            .build()
        return execute(req)
    }

    /**
     * GET /api/1.0/customers/{id}/payment-methods
     *
     * Newer API versions (2024+) flattened the card fields out of a nested
     * "card": {...} object up to the top of each payment-method entry, e.g.
     * "card_last_four" / "card_brand". This parser accepts both shapes.
     *
     * Returns the raw response body alongside the list so callers can show it
     * for debugging when the list comes back empty.
     */
    data class SavedCardsResponse(val cards: List<SavedCard>, val rawBody: String)

    suspend fun listSavedCards(customerId: String): SavedCardsResponse {
        val req = newRequest("/api/1.0/customers/$customerId/payment-methods")
            .get()
            .build()
        val (rawBody, arr) = withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: $body")
                val parsed = if (body.isBlank()) JSONArray() else JSONArray(body)
                body to parsed
            }
        }
        val out = mutableListOf<SavedCard>()
        for (i in 0 until arr.length()) {
            val pm = arr.getJSONObject(i)
            // Some entries may be non-card payment methods (e.g. wallets) —
            // skip those, but only if "type" is present and not "card".
            val type = pm.optString("type")
            if (type.isNotEmpty() && type != "card") continue

            val cardObj = pm.optJSONObject("card") // legacy nested shape
            val lastFour = cardObj?.optString("last_four")?.takeIf { it.isNotBlank() }
                ?: pm.optString("card_last_four").takeIf { it.isNotBlank() }
                ?: pm.optString("last_four", "????")
            val brand = (cardObj?.optString("brand") ?: pm.optString("card_brand"))
                .ifBlank { null }

            out.add(
                SavedCard(
                    id = pm.getString("id"),
                    lastFour = lastFour,
                    brand = brand,
                    savedFor = pm.optString("saved_for", "merchant"),
                ),
            )
        }
        return SavedCardsResponse(cards = out, rawBody = rawBody)
    }

    /**
     * POST /api/orders/{order_id}/payments
     *
     * Charges a previously saved payment method without showing any UI.
     * Requires the card to have been saved with savePaymentMethodFor = MERCHANT
     * and the order to be linked to the same customer.
     *
     * Note: the legacy /api/1.0/orders/{id}/confirm endpoint is deprecated
     * and only kept alive for existing integrations — the new path drops
     * the /1.0/ prefix and uses /payments.
     */
    suspend fun payWithSavedCard(orderId: String, paymentMethodId: String): JSONObject {
        val payload = JSONObject().apply {
            put(
                "saved_payment_method",
                JSONObject().apply {
                    put("type", "card")
                    put("id", paymentMethodId)
                    // "merchant" = server-initiated (no 3DS env block needed).
                    // "customer" would also require an "environment" object.
                    put("initiator", "merchant")
                },
            )
        }
        val url = "/api/orders/$orderId/payments"
        val req = newRequest(url)
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()
        return withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                android.util.Log.i(
                    "RevolutDemo",
                    "POST $BASE_URL$url payload=$payload -> ${resp.code} body=$body",
                )
                if (!resp.isSuccessful) {
                    throw IOException("HTTP ${resp.code} on $url: $body")
                }
                if (body.isBlank()) JSONObject() else JSONObject(body)
            }
        }
    }
}
