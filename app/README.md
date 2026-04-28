# Revolut sandbox demo (Android, single screen)

A one-page Android app that walks the full Revolut Merchant API
"save card on first payment, charge it later" flow:

1. **Create customer** (`POST /api/1.0/customers`)
2. **Create order** (`POST /api/1.0/orders` with `customer.id`)
3. **Save card with payment** — card details collected via the **Revolut
   Merchant Card Form SDK** with `savePaymentMethodFor = MERCHANT`, which
   both charges the order and persists the card against the customer.
4. **Pay with saved card next time** — `POST /api/1.0/orders/{id}/payments`
   with a `saved_payment_method` body. No UI, no SDK, no 3DS prompt.

The customer id, saved payment method id, and last four digits are kept in
`SharedPreferences`. Tap **Reset local state** to clear them and run the
first-payment path again.

## ⚠️ Security warnings — read before doing anything else

1. **Rotate the sandbox secret key now.** It was shared in a chat thread,
   and even sandbox keys are credentials. Open the Revolut Business
   sandbox dashboard → Merchant API → revoke and regenerate. Then update
   `REVOLUT_SECRET_KEY` in `app/build.gradle.kts`.

2. **Do not ship a build like this to production.** The Android app
   embeds your secret key, which means anyone who downloads the APK can
   pull it out with `apktool` in under a minute and create unlimited
   orders, refunds, payments, etc. on your account. For a real app:
   - The Android app holds only the **public** key (`pk_...`).
   - Calls to `/customers`, `/orders`, `/orders/{id}/payments`, and
     `/customers/{id}/payment-methods` go to **your** backend, which
     proxies them with the secret key.
   - The only thing the app sends from the SDK is the `token` your
     backend returns from `POST /orders`.

   Each method in `RevolutApi.kt` is a one-to-one mapping of an HTTP
   call you should be making server-side. Lift the file into your
   backend project and have the Android app call your own endpoints
   instead.

3. The Revolut Merchant Card Form SDK still needs a **public** key
   (`pk_...`). Paste it into `REVOLUT_PUBLIC_KEY` in `build.gradle.kts`
   — the app won't initialise the SDK without it.

## Files

```
app/
├── build.gradle.kts                  ← SDK dep, BuildConfig keys
└── src/main/
    ├── AndroidManifest.xml           ← INTERNET permission, app class
    ├── java/com/example/revolutdemo/
    │   ├── RevolutDemoApp.kt         ← SDK.configure() at startup
    │   ├── RevolutApi.kt             ← Merchant API HTTP wrapper
    │   └── MainActivity.kt           ← The whole flow
    └── res/
        ├── layout/activity_main.xml  ← email field + 3 buttons + status
        └── values/strings.xml
```

Drop the `app/` folder into a fresh Android Studio "Empty Activity" project
(Kotlin, min SDK 24+) and replace the generated `app/` with this one.
Make sure the project-level `settings.gradle.kts` has `mavenCentral()`.

## Sandbox test cards

Use the published Revolut sandbox test cards. The basic ones:

| Number              | Behaviour            |
|---------------------|----------------------|
| 4929 4212 3460 0821 | Success, no 3DS      |
| 5467 0468 7965 4321 | Success, with 3DS    |
| 4485 0192 5621 1166 | Decline (do_not_honor) |

Any future expiry, any 3-digit CVV, any name. Full list is in Revolut's
docs under "Test cards in the Sandbox environment".

## Walking the flow

1. Run the app on a sandbox device/emulator with internet.
2. Type any email, e.g. `demo+1@example.com`, and tap
   **Create customer & pay £10**.
3. The Revolut card form opens. Enter `4929 4212 3460 0821`, future
   expiry, any CVV. Tap pay.
4. Back in the app, the status block now shows the customer id, the
   saved payment method id, and `**** 0821`. The first button hides;
   the **Pay £10 with saved card ****0821** button appears.
5. Tap it. No UI — the app calls `POST /orders` then
   `POST /orders/{id}/payments` server-to-server and toasts the final
   payment state (`captured` on success).
6. **Reset local state** wipes the stored ids so you can run step 2
   again with the same or a different email.

## A few things worth knowing

- **`savePaymentMethodFor: MERCHANT` vs `CUSTOMER`.** This demo uses
  `MERCHANT` because the repeat-payment step is server-initiated with
  no customer present. If you only need 1-click checkout *with* the
  customer on screen, `CUSTOMER` is fine — but then you can't use the
  `Pay for an order` endpoint to charge it without showing the SDK
  again. `MERCHANT` covers both cases.
- **`orderId` in `CardPaymentParams` means the public `token`**, not
  the internal order id. The class names this field confusingly — the
  code passes `order.token`, which is correct.
- **Linking `customer.id` on the order does not save the card.** It
  only associates the order with the customer record. The save flag
  belongs on the SDK call.
- **Listing saved cards.** After a successful first payment, the demo
  calls `GET /customers/{id}/payment-methods` and picks the most
  recent card with `saved_for: "merchant"`. In a real app you'd also
  match against the `fingerprint` or `last_four` returned by the
  payment to be sure you stored the right one.
