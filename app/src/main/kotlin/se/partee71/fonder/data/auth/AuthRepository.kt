package se.partee71.fonder.data.auth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Minimal representation av inloggad användare (utökas i auth-issuet). */
data class AuthUser(val id: String, val displayName: String?, val email: String?)

/**
 * Kontrakt för inloggning (Firebase Auth + Google Credential Manager, auth-issue).
 * Stubben håller ett null-tillstånd (utloggad) tills auth implementeras.
 */
interface AuthRepository {
    val currentUser: Flow<AuthUser?>
    suspend fun signOut()
}

@Singleton
class StubAuthRepository @Inject constructor() : AuthRepository {
    // TODO(auth-issue): koppla in Credential Manager + Firebase.
    override val currentUser: Flow<AuthUser?> = MutableStateFlow(null)
    override suspend fun signOut() = Unit
}
