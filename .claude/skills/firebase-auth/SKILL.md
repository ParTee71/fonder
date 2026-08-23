---
name: firebase-auth
description: Google-inloggning i Fonder — Firebase Auth via Androids Credential Manager (TP-6). Ladda denna när du rör inloggning, utloggning, inloggat tillstånd, AuthRepository, google-services.json eller något som ska hända bara när användaren är inloggad. Covers CredentialManager + GetSignInWithGoogleOption-mönstret, som ersätter utfasade GoogleSignIn. Trigger-ord: inloggning, logga in, logga ut, auth, Firebase, FirebaseAuth, Credential Manager, GoogleIdTokenCredential, AuthRepository, AuthUser, SignInException, google-services.json, default_web_client_id, SHA-1.
---

# Google-inloggning (Firebase Auth + Credential Manager)

Fonder använder **Credential Manager + Firebase Auth**, inte den utfasade
`GoogleSignIn`-API:n. Byggd i issue #106 (TP-6).

## Arkitektur

```
SettingsScreen (LocalContext.current = aktivitetens context)
  → SettingsViewModel.signIn(activityContext)
    → AuthRepository.signInWithGoogle(activityContext): Result<AuthUser>
      → CredentialManager.getCredential()          — Googles kontoväljare
      → GoogleIdTokenCredential.createFrom()       — plockar ut ID-token
      → FirebaseAuth.signInWithCredential()        — växlar token mot en session
```

Inloggat tillstånd läses **aldrig** ur svaret ovan, utan alltid ur
`AuthRepository.currentUser`.

## Kontraktet (`data/auth/`)

```kotlin
data class AuthUser(val id: String, val displayName: String?, val email: String?)

class SignInException(val reason: Reason, cause: Throwable? = null) : Exception(cause) {
    enum class Reason { CANCELLED, NO_CREDENTIAL, FAILED }
}

interface AuthRepository {
    val currentUser: Flow<AuthUser?>
    suspend fun signInWithGoogle(activityContext: Context): Result<AuthUser>
    suspend fun signOut()
}
```

`FirebaseAuthRepository` är implementationen; bindningen ligger i `RepositoryModule`.

## Fem regler som är dyrköpta

**1. `AuthUser`, aldrig `FirebaseUser`.** Interfacet lämnar ut appens egen typ. Läcker
Firebases typ ut i ViewModel eller UI går ingetdera att testa utan Firebase på klassvägen —
och hela `SettingsViewModelTest` bygger på att `AuthRepository` går att fejka med
`object : AuthRepository { … }`.

**2. `GetSignInWithGoogleOption`, inte `GetGoogleIdOption`.** Den förra öppnar Googles
helsidesväljare. Den senare visar ett bottenark och faller med "Failed to retrieve an ID
token" när ingen inloggning redan är cachad — alltså precis vid den första inloggningen, den
enda som säkert sker.

**3. Ett avbrutet kontoval är inget fel.** `CANCELLED` filtreras bort i ViewModel:en och når
aldrig UI:t. Visas det som ett fel får användaren en röd rad för något hen själv nyss gjorde
med flit. Övriga fel kvitteras bort som engångshändelser, samma princip som backup-meddelandet
(issue #78).

**4. `currentUser` seedar sitt första värde innan lyssnaren registreras.** Det är en spärr,
inte en optimering: flödet ingår i `SettingsViewModel`s `combine`-kedja, och `combine` ger
ingenting förrän varje gren emitterat minst en gång. Uteblir den första emissionen fryser
**hela** Inställningar på sitt initialvärde — tema och backup med — för att inloggningen inte
svarade.

**5. Utloggningen rensar Credential Managers cachade val *före* Firebase-utloggningen.** Utan
det kommer nästa inloggning tillbaka med samma konto utan att fråga, vilket ser ut som att
utloggningen inte tog.

## Inloggningen bär ingen data

Den är förutsättningen för molnbackupen (TP-7 steg 2), inget mer. Sessionen är enhetsbunden
och ingår **inte** i backup-kontraktet (NFR-1) — en session hör till enheten, inte till
portföljen. Kortets text i Inställningar säger det rakt ut, så att "inloggad" inte förväxlas
med "säkerhetskopierad". Ändra inte den texten utan att ändra verkligheten först.

Av samma skäl pekar manifestet ut `backup_rules.xml` och `data_extraction_rules.xml`: båda är
**include**-listor (`fonder.db` + `datastore`), så Firebase-sessionen kommer inte med i Auto
Backup. Skyddet ligger i att den inte står med — inte i att den exkluderas. Lägg inte till
`<include>` för hela filkatalogen.

## Något som bara ska hända inloggad

Kontrollera `currentUser` och **hoppa över tyst**, returnera inte ett fel:

```kotlin
if (authRepository.currentUser.first() == null) return Result.success()
```

En bakgrundskörning som misslyckas för att ingen är inloggad är inte ett fel — det är ett
läge appen ska tåla.

## google-services.json

Filen ligger i `app/` och är **incheckad** — `google-services`-pluginet läser den vid varje
bygge och genererar `R.string.default_web_client_id`. Utan den går CI inte att köra.

Kraven på filen för `se.partee71.fonder`:

- **två** `client_type: 1` — en Android-klient per registrerat SHA-1 (debug + release).
  Saknas releasenyckelns går inloggning i debug men inte i en signerad APK.
- **en** `client_type: 3` — projektets webbklient, den som blir `default_web_client_id`.

Fonder ligger som andra app i Firebase-projektet `dagboken-711d2`; medgivandeskärmen är
därför gemensam, vilket syns genom att kontoväljaren visar projektets appnamn. Hela
uppsättningen: [docs/GOOGLE-SETUP.md](../../../docs/GOOGLE-SETUP.md).

## Felkategorier

| Undantag | Betyder | `Reason` | UI |
|---|---|---|---|
| `GetCredentialCancellationException` | Användaren stängde väljaren | `CANCELLED` | inget alls |
| `NoCredentialException` | Inget Google-konto på enheten, eller Play-tjänster saknas | `NO_CREDENTIAL` | "Lägg till ett konto…" |
| Övriga | Nätverk, Firebase-avslag, okänt | `FAILED` | "Försök igen" |

Ett fel som **inte** är en `SignInException` faller tillbaka på `FAILED` i ViewModel:en — det
finns ett test för just det, så att ett oväntat undantag inte ger en knapp som bara slutar
snurra.

## Tester

`AuthRepository` fejkas; `FirebaseAuthRepository` testas inte (den är ren Firebase-plumbing
och går inte att köra utan Play-tjänster). Sömmen ligger på interfacet, och det är där
täckningen ska ligga: lyckad inloggning, avbrutet val, orsaksfel, fel utan orsak, kvittering,
utloggning och dubbeltrycksspärr — se `SettingsViewModelTest`. Instrumenttesterna renderar
`SettingsContent` med handskrivet `UiState`, aldrig en riktig inloggning.
