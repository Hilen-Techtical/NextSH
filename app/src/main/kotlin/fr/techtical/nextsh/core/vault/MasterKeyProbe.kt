// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.vault

import android.security.keystore.UserNotAuthenticatedException
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import java.security.Provider
import java.security.ProviderException
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.NoSuchPaddingException
import kotlin.random.Random
import timber.log.Timber

private const val ANDROID_KEYSTORE = "AndroidKeyStore"

/**
 * Fournisseur JCA qu'Android installe dans chaque processus pour les clés du Keystore, au-dessus du
 * BouncyCastle de la plateforme. Même nom de l'API 23 à aujourd'hui, mais interne : si un jour il
 * manque, [keystoreAesGcmCipher] retombe sur la sélection par défaut.
 */
internal const val ANDROID_KEYSTORE_CIPHER_PROVIDER = "AndroidKeyStoreBCWorkaround"
private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"

/** Pause maximale, en millisecondes, avant le nouvel essai d'une erreur transitoire du Keystore. */
private const val RETRY_PAUSE_MAX_MS = 100L

/** Message fixe de l'échec fermé sur clé absente : aucun secret, pas même l'alias. */
internal const val MASTER_KEY_MISSING_MESSAGE = "Clé maître du vault absente du Keystore Android"

/** Message fixe de la [UserNotAuthenticatedException] déduite d'une NullPointerException du chiffrement. */
internal const val AUTH_REQUIRED_MESSAGE = "Authentification requise pour utiliser la clé maître du vault"

/**
 * Sonde de la clé maître du vault, passée AVANT la première création d'EncryptedSharedPreferences.
 *
 * Pourquoi : EncryptedSharedPreferences déchiffre ses jeux de clés Tink une seule fois, à la
 * création de l'instance, avec la clé maître du Keystore (authentification requise, fenêtre de
 * [AUTH_VALIDITY_SECONDS] s). Tink 1.8 contrôle d'abord cette clé dans
 * `AndroidKeystoreKmsClient.getAead()` (`validateAead`, un aller-retour chiffrement puis
 * déchiffrement). Quand la fenêtre a expiré, il y a deux issues, toutes deux fausses pour le vault :
 * - si la plateforme lève `UserNotAuthenticatedException`, Tink l'AVALE (c'est une
 *   `GeneralSecurityException`), consigne « cannot use Android Keystore, it'll be disabled » et
 *   poursuit, par exemple en relisant le jeu de clés chiffré comme s'il était en clair (ou, sans jeu
 *   de clés stocké, la `KeyStoreException` de Tink « exists but is unusable ») ;
 * - `Cipher.getInstance` sans fournisseur choisit le fournisseur à `init` et relance l'exception du
 *   premier qui échoue. BouncyCastle, placé en tête par App, passe le premier sur une clé du Keystore
 *   et lève une `NullPointerException` (`KeyParameter` : la clé n'a pas d'encodage) quand le Keystore
 *   refuse l'opération, par exemple `KEY_USER_NOT_AUTHENTICATED`. Ce n'est pas une
 *   `GeneralSecurityException` : elle traverse Tink et remonte à l'appelant, et l'erreur réelle du
 *   Keystore est perdue. Sur tous les appareils, pas seulement chez un constructeur.
 *
 * La sonde désigne donc le fournisseur `AndroidKeyStoreBCWorkaround` par son nom (voir
 * [keystoreAesGcmCipher]) pour voir l'erreur réelle. Ne pas déplacer BouncyCastle dans App : la
 * sauvegarde, la génération des clés SSH et le repli de Tink en dépendent.
 *
 * Dans les deux cas [VaultAuthExpiredException] n'est jamais levée.
 *
 * La sonde refait un chiffrement à blanc comme Tink, mais hors de Tink : l'erreur d'authentification
 * remonte à l'appelant et Tink n'atteint jamais ce repli. Elle ne lit ni n'écrit aucune donnée et ne
 * touche à aucun matériau de clé : elle initialise un chiffreur AES-GCM, chiffre un seul octet nul
 * (Tink en chiffre dix, tirés au hasard) puis termine l'opération, et jette le résultat. Terminer
 * l'opération est volontaire : une opération Keystore laissée ouverte ne serait libérée que par
 * élagage ou par le ramasse-miettes, et StrongBox n'a que très peu d'emplacements d'opération.
 *
 * Table des issues, par étape :
 * - [android.security.keystore.UserNotAuthenticatedException] (chargement ou chiffrement) : fenêtre
 *   expirée, propagée telle quelle, qu'elle soit levée à l'initialisation ou à la fin de
 *   l'opération, et jamais rejouée. [VaultManager] la traduit en [VaultAuthExpiredException].
 * - `NullPointerException` du CHIFFREMENT à blanc : ne vient plus que du repli sur la sélection par
 *   défaut, où elle masque l'erreur du Keystore et signifie le plus souvent « authentification
 *   requise ». Elle devient une [UserNotAuthenticatedException] à message fixe,
 *   qui a cette NullPointerException pour cause, jamais rejouée. C'est sûr : cela ne peut qu'amener à
 *   demander une authentification, jamais à accorder l'accès. Si la cause est autre, l'utilisateur
 *   voit l'invite, l'unique nouvel essai qui suit échoue de nouveau et le message d'échec existant
 *   s'affiche, sans boucle.
 * - [android.security.keystore.KeyPermanentlyInvalidatedException] : propagée telle quelle, comme
 *   exception typée, vers l'appelant, et jamais rejouée. Sa prise en charge dans l'UI reste un
 *   suivi à traiter.
 * - `ProviderException` (chargement ou chiffrement) ou `NullPointerException` du CHARGEMENT de la
 *   clé (erreurs transitoires du Keystore, que Tink rejoue aussi) : un seul nouvel essai après une
 *   courte pause aléatoire (jusqu'à 100 ms). Si le second essai échoue, son exception est propagée
 *   (ou, pour une NullPointerException du chiffrement, sa traduction ci-dessus) et la première lui
 *   est jointe en supprimée.
 * - toute autre exception de la sonde (Keystore défaillant) : propagée aussi, jamais avalée. Un
 *   Keystore cassé doit se voir, pas retomber dans le repli de Tink.
 * - clé absente : [KeyStoreException] au message fixe (échec fermé). La sonde passe après
 *   `VaultPrefsOpener.ensureMasterKey()`, qui crée la clé : son absence est anormale, et sans jeu
 *   de clés stocké Tink créerait sa propre clé sous cet alias, SANS authentification utilisateur.
 *
 * Une fois EncryptedSharedPreferences ouvert, le Keystore n'est plus sollicité par le processus :
 * la sonde ne compte donc qu'à la première ouverture.
 */
internal fun interface MasterKeyProbe {
    fun probe()
}

/**
 * Sonde réelle : charge la clé [alias] depuis le provider `AndroidKeyStore`, puis lui fait chiffrer
 * un octet en AES-GCM, comme le fait Tink, en terminant l'opération. Les étapes et la pause
 * ([pauseBeforeRetry]) sont injectables pour tester la logique sur la JVM, où le Keystore Android
 * n'existe pas, sans attendre. [authRequired] fabrique l'erreur déduite d'une NullPointerException du
 * chiffrement : l'android.jar des tests unitaires ignore les arguments des constructeurs
 * Android, la cause n'y serait pas vérifiable autrement.
 *
 * [alias] est l'alias exact que `MasterKey.Builder(context, alias)` crée dans le Keystore, et que
 * Tink désigne par l'URI `android-keystore://<alias>`.
 */
internal class KeystoreMasterKeyProbe(
    private val alias: String = AUTH_MASTER_KEY_ALIAS,
    private val loadKey: (String) -> Key? = ::loadAndroidKeystoreKey,
    private val dryEncrypt: (Key) -> Unit = ::dryEncryptAesGcm,
    private val pauseBeforeRetry: () -> Unit = ::sleepRandomAmount,
    private val authRequired: (Throwable) -> UserNotAuthenticatedException = ::authRequiredFrom,
) : MasterKeyProbe {

    override fun probe() {
        try {
            probeOnce()
        } catch (first: Exception) {
            if (!isTransient(first)) throw first
            pauseBeforeRetry()
            try {
                probeOnce()
            } catch (second: Exception) {
                if (second !== first) second.addSuppressed(first)
                throw second
            }
        }
    }

    private fun probeOnce() {
        val key = loadKey(alias) ?: throw KeyStoreException(MASTER_KEY_MISSING_MESSAGE)
        try {
            dryEncrypt(key)
        } catch (e: NullPointerException) {
            // Ne vient plus que du repli sur la sélection par défaut (fournisseur du Keystore
            // absent) : BouncyCastle, en tête, lève une NullPointerException de KeyParameter quand
            // le Keystore refuse l'opération (voir MasterKeyProbe). Ce n'est pas une erreur
            // transitoire, et la traduire ne peut qu'amener à demander une authentification.
            throw authRequired(e)
        }
    }

    /**
     * Rejouées : les `ProviderException` des deux étapes et les `NullPointerException` du seul
     * chargement de la clé (celles du chiffrement sont traduites par [probeOnce], donc jamais ici).
     * Jamais l'expiration ni l'invalidation de la clé.
     */
    private fun isTransient(e: Exception): Boolean =
        e is ProviderException || e is NullPointerException
}

/** [UserNotAuthenticatedException] à message fixe, qui a [cause] pour cause (constructeur depuis l'API 23). */
internal fun authRequiredFrom(cause: Throwable): UserNotAuthenticatedException =
    UserNotAuthenticatedException(AUTH_REQUIRED_MESSAGE, cause)

internal fun loadAndroidKeystoreKey(alias: String): Key? =
    KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.getKey(alias, null)

internal fun dryEncryptAesGcm(key: Key) {
    runDryEncryption(keystoreAesGcmCipher(), key)
}

/**
 * Chiffreur AES-GCM du fournisseur du Keystore, désigné par son nom. Sans nom, Cipher choisit le
 * fournisseur à `init` et relance l'exception du PREMIER qui échoue : BouncyCastle, placé en tête par
 * App, échoue toujours le premier sur une clé du Keystore (NullPointerException de `KeyParameter` : la
 * clé n'a pas d'encodage) et l'erreur réelle du Keystore est perdue. Repli sur la sélection par défaut
 * si le fournisseur manque ou n'offre pas la transformation. [findProvider] est injectable pour les
 * tests.
 */
internal fun keystoreAesGcmCipher(
    findProvider: (String) -> Provider? = { Security.getProvider(it) },
): Cipher {
    val provider = findProvider(ANDROID_KEYSTORE_CIPHER_PROVIDER)
    if (provider?.getService("Cipher", AES_GCM_TRANSFORMATION) != null) {
        try {
            return Cipher.getInstance(AES_GCM_TRANSFORMATION, provider)
        } catch (e: NoSuchAlgorithmException) {
            // Repli ci-dessous.
        } catch (e: NoSuchPaddingException) {
            // Repli ci-dessous.
        }
    }
    Timber.w("Vault master key probe: Keystore cipher provider unavailable, default selection")
    return Cipher.getInstance(AES_GCM_TRANSFORMATION)
}

/**
 * Initialise [cipher] en chiffrement avec [key], puis termine l'opération sur une entrée d'un octet
 * nul : le résultat est jeté. Aucune exception n'est interceptée, ni à l'initialisation ni à la fin.
 */
internal fun runDryEncryption(cipher: Cipher, key: Key) {
    cipher.init(Cipher.ENCRYPT_MODE, key)
    cipher.doFinal(ByteArray(1))
}

/** Pause aléatoire de 0 à [RETRY_PAUSE_MAX_MS] ms, qui désynchronise les nouveaux essais concurrents. */
internal fun sleepRandomAmount() {
    try {
        Thread.sleep(Random.nextLong(RETRY_PAUSE_MAX_MS + 1))
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
    }
}
