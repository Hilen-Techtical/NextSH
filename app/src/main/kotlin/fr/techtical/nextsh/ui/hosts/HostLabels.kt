// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.hosts

import androidx.annotation.StringRes
import fr.techtical.nextsh.R
import fr.techtical.nextsh.domain.model.AuthType

/**
 * Les mots de l'écran des hôtes qui dépendent d'un énuméré de `:shared`.
 *
 * `:shared` ne connaît pas les ressources Android : le mode d'authentification d'un hôte est dit ici,
 * en ressource, dans la langue de l'utilisateur ("Mot de passe", "Clé SSH"). C'est le mot en clair
 * du sélecteur du formulaire et la description d'accessibilité de la carte ; le badge de la carte
 * garde ses codes courts (PWD, SSH_KEY), les mêmes que sur le Desktop.
 */
@StringRes
internal fun authTypeLabelRes(type: AuthType): Int = when (type) {
    AuthType.PASSWORD -> R.string.auth_type_password
    AuthType.SSH_KEY -> R.string.auth_type_ssh_key
    AuthType.CERTIFICATE -> R.string.auth_type_certificate
    AuthType.FIDO2 -> R.string.auth_type_fido2
    AuthType.BIOMETRIC_KEY -> R.string.auth_type_biometric_key
}
