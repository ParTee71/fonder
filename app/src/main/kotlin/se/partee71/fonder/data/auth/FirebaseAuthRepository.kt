package se.partee71.fonder.data.auth

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.tasks.await
import se.partee71.fonder.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google-inloggning via **Credential Manager + Firebase Auth** (TP-6).
 *
 * Kedjan är: Credential Manager visar kontoväljaren och lämnar ett Google-**ID-token**, som
 * växlas mot en Firebase-session. Firebase äger sessionen och persisterar den själv, så
 * [currentUser] överlever appstarter utan att appen sparar något eget.
 */
@Singleton
class FirebaseAuthRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : AuthRepository {

    private val auth = FirebaseAuth.getInstance()
    private val credentialManager = CredentialManager.create(context)

    /**
     * `callbackFlow` runt Firebases egen lyssnare i stället för en engångsläsning: sessionen kan
     * ändras utan att appen bad om det (token gick ut, kontot togs bort på enheten), och då ska
     * Inställningar sluta visa en inloggad användare av sig självt.
     *
     * Det inledande `trySend` är en **spärr, inte en optimering**. `AuthStateListener` ska anropas
     * direkt när den registreras, men flödet ingår i `SettingsViewModel`s `combine`-kedja, och
     * `combine` ger ingenting alls förrän varje gren emitterat minst en gång. Uteblev den första
     * emissionen skulle därför hela Inställningar frysa på sitt initialvärde — tema, backup och
     * kontotyp med — för att inloggningen inte svarade. Ett dubblettvärde kostar ingenting;
     * `distinctUntilChanged` fångar det.
     */
    override val currentUser: Flow<AuthUser?> = callbackFlow {
        trySend(auth.currentUser?.toAuthUser())
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.toAuthUser()) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }.distinctUntilChanged()

    override suspend fun signInWithGoogle(activityContext: Context): Result<AuthUser> = try {
        // Genereras av google-services-pluginet ur app/google-services.json (client_type: 3).
        // Kompileringsfel här betyder att filen saknas eller saknar webbklient — se
        // docs/GOOGLE-SETUP.md.
        val webClientId = activityContext.getString(R.string.default_web_client_id)

        // GetSignInWithGoogleOption, inte GetGoogleIdOption: den förra öppnar Googles
        // helsidesväljare, den senare ett bottenark som faller med "Failed to retrieve an ID
        // token" när ingen inloggning redan är cachad — alltså just vid första inloggningen.
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(webClientId).build())
            .build()

        val credential = credentialManager.getCredential(activityContext, request).credential
        val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
        val user = auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null))
            .await()
            .user
            ?: error("Firebase gav ingen användare trots lyckad inloggning")

        Result.success(user.toAuthUser())
    } catch (e: GetCredentialCancellationException) {
        Result.failure(SignInException(SignInException.Reason.CANCELLED, e))
    } catch (e: NoCredentialException) {
        Result.failure(SignInException(SignInException.Reason.NO_CREDENTIAL, e))
    } catch (e: Exception) {
        Result.failure(SignInException(SignInException.Reason.FAILED, e))
    }

    /**
     * Rensar Credential Managers cachade val **före** utloggningen. Utan det kommer nästa
     * inloggning tillbaka med samma konto utan att fråga, vilket ser ut som att utloggningen
     * inte tog.
     */
    override suspend fun signOut() {
        runCatching { credentialManager.clearCredentialState(ClearCredentialStateRequest()) }
        auth.signOut()
    }
}

private fun FirebaseUser.toAuthUser() = AuthUser(id = uid, displayName = displayName, email = email)
