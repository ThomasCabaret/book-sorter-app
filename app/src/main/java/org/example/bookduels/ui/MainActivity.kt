package org.example.bookduels.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

internal sealed interface ProtectedAction {
    data class Rollback(val startSequence: Long, val expectedRevision: Long) : ProtectedAction
    data class Restore(val expectedRevision: Long) : ProtectedAction
}

class MainActivity : FragmentActivity() {
    private val model: MainViewModel by viewModels()

    private val csvLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) model.importCsv(uri) }

    private val bundleLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/gzip")
    ) { uri -> if (uri != null) model.exportBundle(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BookDuelsTheme {
                val state by model.uiState.collectAsStateWithLifecycle()
                BookDuelsApp(
                    state = state,
                    onDuelAction = model::record,
                    onUndoLatestDecision = model::undoLatestDecision,
                    onProtectedAction = ::authenticateThenApply,
                    onExport = { bundleLauncher.launch("bookduels-backup.tar.gz") },
                    onImportCsv = { csvLauncher.launch(arrayOf("*/*")) },
                    onAddBook = model::addManually,
                    onToggleBook = model::setEnabled,
                    onMessageDismissed = model::dismissMessage,
                )
            }
        }
    }

    /** Never downgrade to an unauthenticated modification of history. */
    private fun authenticateThenApply(action: ProtectedAction) {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val available = BiometricManager.from(this).canAuthenticate(authenticators)
        if (available != BiometricManager.BIOMETRIC_SUCCESS) {
            model.showMessage("Configurer un verrouillage sécurisé (PIN/mot de passe/biométrie) pour modifier l'historique")
            return
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    when (action) {
                        is ProtectedAction.Rollback -> model.invalidateFrom(action.startSequence, action.expectedRevision)
                        is ProtectedAction.Restore -> model.reinstateLatest(action.expectedRevision)
                    }
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    model.showMessage("Authentification non validée : $errString")
                }
            }
        )
        val title = when (action) {
            is ProtectedAction.Rollback -> "Confirmer l'annulation de votes"
            is ProtectedAction.Restore -> "Confirmer la restauration de votes"
        }
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle("Vérification par le téléphone")
                .setAllowedAuthenticators(authenticators)
                .build()
        )
    }
}
