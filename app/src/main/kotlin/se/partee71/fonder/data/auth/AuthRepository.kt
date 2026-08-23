package se.partee71.fonder.data.auth

import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * Minimal representation av inloggad användare (TP-6).
 *
 * Egen domäntyp och inte Firebases `FirebaseUser`: ViewModels och UI ska kunna testas utan
 * Firebase på klassvägen, och inloggningsleverantören ska gå att byta utan att skärmarna vet om
 * det. Mappningen sker i [FirebaseAuthRepository].
 */
data class AuthUser(val id: String, val displayName: String?, val email: String?)

/**
 * Fel vid inloggning, uppdelat efter **vad användaren ska göra** — inte efter vilket undantag
 * som kastades. Samma form som `BackupFormatException.Reason` (SET-6): ett `Result`-fel med en
 * uppräknad orsak, som UI:t översätter till en svensk sträng.
 */
class SignInException(val reason: Reason, cause: Throwable? = null) : Exception(cause) {
    enum class Reason {
        /** Användaren stängde kontoväljaren. Inget fel — ska aldrig visas som ett. */
        CANCELLED,

        /** Inget Google-konto på enheten, eller Play-tjänster saknas/är för gamla. */
        NO_CREDENTIAL,

        /** Nätverk, Firebase-avslag eller okänt — det användaren kan göra är att försöka igen. */
        FAILED,
    }
}

/**
 * Kontrakt för Google-inloggning (Firebase Auth + Credential Manager, TP-6).
 *
 * Inloggningen bär i sig ingen användardata — den är förutsättningen för molnbackupen
 * (TP-7 steg 2), som är det som faktiskt flyttar data. Ingenting här ingår i
 * backup-kontraktet (NFR-1): en session hör till enheten, inte till portföljen.
 */
interface AuthRepository {

    /** Nuvarande användare, `null` när ingen är inloggad. Emitterar vid varje ändring av inloggningsläget. */
    val currentUser: Flow<AuthUser?>

    /**
     * Visar Googles kontoväljare och växlar vald identitet mot en Firebase-session.
     *
     * Kräver ett **Activity**-context, inte applikationens: Credential Manager ritar sitt UI
     * ovanpå den aktiva aktiviteten. Fel returneras som [SignInException], aldrig kastade —
     * samma princip som `BackupRepository`.
     */
    suspend fun signInWithGoogle(activityContext: Context): Result<AuthUser>

    suspend fun signOut()
}
