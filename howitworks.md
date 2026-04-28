# How the Revolut "save card on first pay, charge it later" flow works

This document describes how the demo integrates the **Revolut Merchant Card
Form SDK** (`com.revolut.payments:merchantcardform:3.1.2`) with the **Revolut
Merchant API** to:

1. Take a customer's first card payment via the SDK card form, **and** save the
   card against the customer.
2. Later, charge that same card server-to-server with no UI ("merchant
   initiated transaction" / MIT).

It also captures every concrete pitfall we hit getting it working, so you can
skip them in the production app.

---

## 1. Architecture at a glance

There are **two** separate Revolut surfaces, on different hosts, with
different auth and different paths. Mixing them up is the source of most
errors:

| Surface | Host | Auth | Used by |
|---|---|---|---|
| **Merchant API** | `sandbox-merchant.revolut.com` (sandbox) / `merchant.revolut.com` (prod) | `Authorization: Bearer sk_…` (secret key) | **Your backend.** Creating customers, creating orders, charging saved cards. |
| **Public/Customer API** | `pay.revolut.com` etc. (called transparently by the SDK) | `pk_…` (public key) | **The SDK only.** You don't call this yourself. |

Cardinal rule: **the secret key must live on your backend, never in the
mobile app.** The demo embeds it for sandbox-device convenience only. See
section 7.

The flow is:

```
[App]                 [Your backend]              [Revolut]
  |                       |                          |
  |--- create customer -->|--- POST /customers ----->|
  |<------ customer_id ---|<-------- id -------------|
  |                       |                          |
  |--- create order ----->|--- POST /orders -------->|   (with customer_id +
  |<- order.id, .token ---|<-- id, public_id --------|    save_payment_method_for)
  |                       |                          |
  |--- launch SDK with order.token (+ savePaymentMethodFor=MERCHANT) ---->|
  |<-- CardPaymentResult.Authorised --------------------------------------|
  |                       |                          |
  |--- get order -------->|--- GET /orders/{id} ---->|
  |<- payment_method.id --|<- payments[0].pm.id -----|   (this is the saved card)
  |                       |                          |
  | …time passes…         |                          |
  |                       |                          |
  |--- create new order ->|--- POST /orders -------->|
  |--- charge saved card->|-- POST /orders/{id}/payments (initiator=merchant) -->|
  |<------ done ----------|<------- state -----------|
```

---

## 2. Prerequisites

1. A **Revolut Business sandbox** account (`sandbox-business.revolut.com`).
2. From the sandbox dashboard → **Settings → API**, get:
   - The **secret key** (`sk_…`) — used by your backend for the merchant API.
   - The **public key** (`pk_…`) — used by the SDK on the device.
3. Both keys MUST come from the **same merchant account**, and both must be
   **sandbox** keys. A mismatch produces `ApiError(errorCode=1022)` after the
   user submits card details — not at SDK init time, which makes it look like
   a card error when it isn't.

---

## 3. Gradle setup

**`gradle/libs.versions.toml`** — pinned versions:

```toml
[versions]
agp = "9.2.0"
```

**`app/build.gradle.kts`**:

```kotlin
plugins {
    id("com.android.application")
    // Do NOT also apply id("org.jetbrains.kotlin.android"):
    // AGP 9 has built-in Kotlin and registers the `kotlin` extension itself.
    // Re-applying the standalone Kotlin plugin causes:
    //   "Cannot add extension with name 'kotlin', as there is an extension
    //    already registered with that name."
}

android {
    namespace = "com.example.revolutdemo"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        // Pasted into BuildConfig so they're available without resources lookup.
        // Move REVOLUT_SECRET_KEY off the device for production (see §7).
        buildConfigField("String", "REVOLUT_SECRET_KEY", "\"sk_…\"")
        buildConfigField("String", "REVOLUT_PUBLIC_KEY",  "\"pk_…\"")
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// In AGP 9, kotlinOptions { jvmTarget = "17" } inside `android { }` is gone —
// use the top-level kotlin { compilerOptions { … } } block instead.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.revolut.payments:merchantcardform:3.1.2")
    // …+ AndroidX, OkHttp, coroutines as in the demo.
}
```

---

## 4. SDK initialization

Configure the SDK **once at process start**, in `Application.onCreate`:

```kotlin
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
```

Register this class in `AndroidManifest.xml` via `android:name=".RevolutDemoApp"`.

The SDK's actual API surface in 3.1.2 lives under
`com.revolut.cardpayments.api.*` — **not** `com.revolut.payments.card.*` like
some older docs/snippets show. The classes you'll touch are:

```kotlin
import com.revolut.cardpayments.api.CardPaymentLauncher
import com.revolut.cardpayments.api.CardPaymentParams
import com.revolut.cardpayments.api.CardPaymentResult
import com.revolut.cardpayments.core.api.UserType
```

---

## 5. Backend calls (Merchant API)

All of these go to `https://sandbox-merchant.revolut.com` with the `sk_…`
key in the `Authorization: Bearer …` header and a `Revolut-Api-Version`
header. The demo pins `2025-12-04`; pick the version current to your
integration date and freeze it explicitly so the API doesn't silently
change shape on you.

### 5a. Create a customer

```
POST /api/1.0/customers
{
  "email": "user@example.com"
}
→ { "id": "<customer_id>", … }
```

Persist `customer_id` for that user — you'll attach every future order to it.

### 5b. Create an order

```
POST /api/1.0/orders
{
  "amount": 1000,                    // minor units, e.g. 1000 = £10.00
  "currency": "GBP",
  "description": "…",
  "customer_id": "<customer_id>",    // top-level, NOT nested under "customer"
  "save_payment_method_for": "merchant"   // backend persistence; see below
}
→ {
    "id":        "<internal_order_id>",
    "public_id": "<order_token>",     // older API versions called this "token"
    …
  }
```

Two non-obvious points here:

- **Customer linking field.** Under newer `Revolut-Api-Version` headers
  (anything 2024+) the field is **`customer_id` at the top level**. The
  legacy nested `{"customer": {"id": "…"}}` shape is *silently* ignored —
  the order is accepted but ends up with no customer attached, which then
  blows up in the SDK with `ApiError(errorCode=1022)` after the user fills
  out the card form.

- **Saving the card requires both a flag on the order *and* a flag on the
  SDK call.** `save_payment_method_for` on the order tells the backend to
  actually persist the card after the payment succeeds. The SDK's
  `savePaymentMethodFor` flag (next section) controls consent/UI. Without
  the order flag, the payment goes through but the card is never saved,
  and the customer's payment-methods endpoint returns `[]`.

Two id-like fields come back; both are needed and they are **not
interchangeable**:

- `id` — the internal order id. Used in `/api/orders/{id}/payments` (the
  server-side charge endpoint).
- `public_id` (called `token` in older response shapes) — the SDK-facing
  id. This is what you pass to `CardPaymentParams.orderId`.

### 5c. Get an order back (post-payment, to read the saved card id)

```
GET /api/1.0/orders/{internal_order_id}
→ {
    "state": "COMPLETED",
    "payments": [
       {
         "payment_method": {
           "id": "<saved_payment_method_id>",
           "type": "card",
           "card": { "card_last_four": "0004", … }
         }
       }
    ]
  }
```

Read `payments[0].payment_method.id` — that's the saved-card id you'll
charge later. Store it.

### 5d. Charge a saved card (server-to-server, no UI)

```
POST /api/orders/{internal_order_id}/payments      ← note: NO /1.0/ prefix
{
  "saved_payment_method": {
    "type": "card",
    "id": "<saved_payment_method_id>",
    "initiator": "merchant"          // server-initiated; no 3DS env needed
  }
}
→ { "state": "AUTHORISED" | "COMPLETED" | … }
```

Specifically the path here:

- Drops `/1.0/` — newer Revolut API versions use unprefixed `/api/orders/…`
  for this endpoint family.
- Uses the **internal `id`**, not the public token.
- The legacy `/api/1.0/orders/{id}/confirm` endpoint is **deprecated** and
  only kept alive for existing integrations. Don't add new code against it.

`initiator: "merchant"` is what makes this a true MIT — the customer is
not present, so no browser environment data is required. (`initiator:
"customer"` would additionally require a 14-field `environment` block for
3DS.)

---

## 6. The SDK call (first payment)

After creating the order (5b), launch the card form:

```kotlin
private val cardLauncher = CardPaymentLauncher(this) { result ->
    onCardPaymentResult(result)
}

// later, having created `order` via RevolutApi.createOrder(...):
cardLauncher.launch(
    CardPaymentParams(
        orderId = order.token,                // public_id, NOT order.id
        email   = email,
        savePaymentMethodFor = UserType.MERCHANT,
    ),
)
```

`CardPaymentLauncher` must be constructed at field-init time (it
registers an `ActivityResultContract` under the hood). Don't lazy-init it
inside `onCreate`.

The result is a sealed `CardPaymentResult`. Make the `when` exhaustive —
**every branch matters**:

```kotlin
when (result) {
    CardPaymentResult.Authorised               -> /* success */
    is CardPaymentResult.Failed                -> /* result.failureReason : FailureReason enum */
    is CardPaymentResult.Declined              -> /* result.failureReason */
    CardPaymentResult.UserAbandonedPayment     -> /* user backed out */
    is CardPaymentResult.Error                 -> /* ApiError, NetworkError,
                                                    GenericError, OrderNotAvailable,
                                                    OrderNotFound, TimeoutError */
}
```

Note: `Authorised` and `UserAbandonedPayment` are `data object`s, so
match by equality (`CardPaymentResult.Authorised`), not by `is`.

`Failed.failureReason` is a `FailureReason` enum (`INSUFFICIENT_FUNDS`,
`EXPIRED_CARD`, …) — **not** an exception with `.message`. Older SDK
snippets that read `result.error.message` are stale.

On `Authorised`, hit step 5c to read the saved payment method id from the
order itself. **Don't** rely on `GET /customers/{id}/payment-methods`
immediately after — there can be propagation delay, and the order
endpoint is the authoritative answer for "did this specific transaction
save a card."

---

## 7. Production / security

The single most important point: **the secret key (`sk_…`) must never ship
in the mobile app**. Anyone can decompile the APK and read it, and a leaked
sandbox key will get you locked out of sandbox; a leaked production key is
an incident.

Production architecture should be:

```
[App] ───► [Your backend] ───► [Revolut Merchant API]
                holds sk_…
```

The mobile app calls **your** endpoints (e.g. `POST /your-api/checkout`),
your backend calls Revolut on its behalf using the secret key, and only
returns to the app the things the SDK needs:

- The order's `public_id` (a.k.a. `token`) for `CardPaymentParams.orderId`.
- (Optionally) the `customer_id` if the app needs to display saved cards.

The app continues to hold only the **public key** (`pk_…`), used solely
to configure the SDK. Public keys are designed to be shippable.

To migrate the demo: move every method on `RevolutApi` to your backend,
replace each one with a call to your own endpoint, and delete
`REVOLUT_SECRET_KEY` from `BuildConfig`.

---

## 8. Things that bit us, and what to check first if it bites you again

| Symptom | Cause | Fix |
|---|---|---|
| `Cannot add extension with name 'kotlin'` at Gradle sync | AGP 9 has built-in Kotlin; re-applying `org.jetbrains.kotlin.android` conflicts. | Remove the standalone Kotlin plugin from `plugins {}`; move `kotlinOptions` to a top-level `kotlin { compilerOptions { … } }`. |
| `Unresolved reference 'card'` / `CardPaymentLauncher` | Wrong package; older docs say `com.revolut.payments.card.*`. | Use `com.revolut.cardpayments.api.*` and `com.revolut.cardpayments.core.api.UserType` (matches the 3.1.2 AAR). |
| `Setup failed: No value for token` after `createOrder` | Newer API returns `public_id`, not `token`. | Read `public_id` first, fall back to `token` for old versions. |
| `ApiError(errorCode=1022)` after the user submits card | Either the public/secret keys belong to different merchants, or the order has no customer attached because the legacy nested `customer.id` shape was silently ignored. | (1) Confirm both keys come from the same sandbox merchant. (2) Use `customer_id` at the top level of the order payload. |
| `Paid, but no saved card. Raw: []` | The order didn't have `save_payment_method_for: "merchant"`, so the backend never persisted the card. | Add the field to the order create payload. The SDK flag alone is not enough. |
| `HTTP 404` on the server-to-server saved-card charge | The `/api/1.0/orders/{id}/payments` and `/api/1.0/orders/{id}/confirm` endpoints don't exist (or are deprecated) in newer API versions. | `POST /api/orders/{order.id}/payments` (no `/1.0/` prefix; **internal** id, not the public token). |
| Saved card `**** ????` in UI | `last_four` lives in different field names depending on API version: nested `card.last_four`, or flat `card_last_four` on the payment_method, or `card.card_last_four`. | Look at all of them; pick the first non-blank. |
| Toasts get truncated mid-error | Default `Toast` clips long strings; the truncation hides the actual API error message. | Log the full request URL + payload + response to logcat under a known tag (the demo uses `RevolutDemo`); filter with `adb logcat -s RevolutDemo`. |
