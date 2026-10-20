# Telegram build editions

One source tree produces two editions on iOS and Android:

- **configured**: the application's Telegram API ID/hash are bundled at build time. Customers see country + phone, code, and (only when Telegram requests it) cloud password. Telegram may also request email verification. No API credential form is presented.
- **standard**: users supply their own API credentials on the device.

## Configure once in GitHub

1. Open [Cinespice → Settings → Secrets and variables → Actions](https://github.com/masalaoats007/Cinespice/settings/secrets/actions).
2. Under **Repository secrets**, click **New repository secret**.
3. Name: `TELEGRAM_API_ID`. Secret: the numeric **App api_id** from **API development tools** at https://my.telegram.org. Click **Add secret**.
4. Add a second repository secret: name `TELEGRAM_API_HASH`, value the **App api_hash** from the same Telegram app. Click **Add secret**.
5. Open the repository’s **Actions** tab. Choose **Build Cinespice IPA** (iOS first) or **Build Cinespice Android APK**.
6. Click **Run workflow**, select the branch containing the fixes (`coderabbit/configure-api-credential-builds/b70ec2b5` until merged; then `IOS`), choose edition **configured**, and run it. Choose **both** if you also want the edition that asks for API credentials.
7. Open the finished run and download the artifact whose name contains **configured**: `Cinespice-configured-unsigned-ipa` for iOS or `Cinespice-configured-Android-arm64-v8a` for most Android phones. The iOS IPA still needs signing.

Use **repository secrets**, not variables or environment secrets. GitHub will hide the saved values; you can replace them later. Do not paste values in chat, source files, workflow YAML, or logs. Adding secrets does not change an already downloaded IPA/APK: build and install a fresh configured artifact. Automatic pull request builds are standard editions and still ask for API credentials.

Release-branch push workflows and the default manual selection produce both editions with distinct artifact names. Pull requests and coding-task branch pushes produce only the standard edition. Manual runs can select either edition or both. Missing/invalid secrets fail the configured build without exposing their values; they never silently produce a customer build with an API form. Both editions use the same application ID and signing arrangement, so installing one replaces the other.

Existing Android Actions APKs use an ephemeral debug signing key. Set up stable production signing before selling/distributing updates; these workflow artifacts are test builds. iOS IPAs remain unsigned and require signing.

## Local builds

Provide `TELEGRAM_API_ID` and `TELEGRAM_API_HASH` in your private build environment, then run from the repository root:

```sh
python3 scripts/configure-telegram.py --mode configured --platform both
```

Or build the edition with on-device API entry, without any secrets:

```sh
python3 scripts/configure-telegram.py --mode standard --platform both
```

Then follow the normal XcodeGen/CocoaPods or Android Gradle build instructions. The generated resources are ignored by Git. Do not distribute them separately. Build secrets prevent accidental repository exposure; bundled credentials remain extractable from an IPA/APK. No account session, login code or cloud password is included.

## Library behavior

Home keeps the existing seven hero slots and shows up to five resumable titles, then a horizontal row of up to ten files per selected channel. Each channel has its own persisted account-scoped sorting preference. Opening it hides the global navigation and shows channel-only search and numbered pages of 30 loaded files. Load more retrieves/indexes another Telegram batch; newly available page numbers appear immediately. Sorting is over the loaded index, not the entire unindexed channel.

Home, Search, Favorites and Downloads are the four floating bottom destinations. Favorites includes Watchlist. The header retains Cinespice, Channels and Profile. File cards have Play and Info buttons. Long press opens saved-list, download, export and history actions. File info has Back and saved-list controls. A full download is suggested when streaming cannot begin because the file size is unknown; otherwise the existing streaming player is used.

Online descriptions use optional, user-triggered Wikidata search. Only the edited lookup title is sent. Results are suggestions, not verified identification; no catalogue covers every filename. Local Telegram captions, file metadata and artwork remain available without it. Wikidata structured data is CC0: https://www.wikidata.org/wiki/Wikidata:Licensing.

Country names/calling codes are a derived subset of https://github.com/mledoze/countries (retrieved 2026-10-20), licensed under ODbL 1.0. The derived databases are `Resources/BuildConfig/countries.json` and `android/app/src/main/assets/countries.json`; they remain under ODbL. See `docs/countries-LICENSE.txt`.

## Login recovery

The login screen accepts either a national number with the selected calling code or a pasted full number beginning with `+` / `00`. Back and Edit number return to the phone form without erasing it. The next submission changes the phone through TDLib’s supported `setAuthenticationPhoneNumber` flow; returning to the form does not send another code by itself.

Code instructions come from Telegram’s `code_info`, including whether delivery is to an existing Telegram session, SMS, or a call. Resend appears only when Telegram supplies a next delivery method and stays disabled until its timeout passes. Telegram controls delivery; third-party applications cannot guarantee SMS. Email verification and cloud password appear only when Telegram requests them.

Connection/setup errors have Retry connection, and the standard edition can change API credentials. Retry reopens the TDLib client with the existing database/key; it does not delete your library or session. Startup ignores stale authorization snapshots and submits TDLib parameters only once per client.
