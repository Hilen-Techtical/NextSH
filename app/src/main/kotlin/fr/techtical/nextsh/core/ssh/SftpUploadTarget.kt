// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

/**
 * Ce qui occupe, sur le serveur, le nom sous lequel un fichier va être envoyé.
 *
 * Le résultat de [SftpManager.checkUploadTarget] : l'écran en tire sa décision avant tout transfert
 * (envoyer, demander s'il faut remplacer, ou refuser). Ce n'est qu'une politesse faite à
 * l'utilisateur : la garantie de ne jamais remplacer un fichier sans confirmation est l'ouverture
 * en [net.schmizz.sshj.sftp.OpenMode.EXCL] de [SftpManager.writeFile], qui refuse un nom pris même si
 * la vérification s'est trompée ou n'a pas pu avoir lieu. Cette garantie vaut pour un nom libre. Un
 * remplacement confirmé ne s'ouvre pas sur le nom lui-même : il passe par un fichier temporaire caché
 * du même dossier, créé en EXCL, qui prend la place de l'ancien fichier une fois complet.
 */
enum class UploadTarget {
    /** Rien à ce nom : l'envoi crée le fichier. */
    FREE,

    /**
     * Un fichier ordinaire, un lien vers un fichier, ou une entrée dont le serveur ne donne pas le
     * type (mode inconnu). L'envoi peut le remplacer, après confirmation.
     */
    FILE,

    /** Un dossier, ou un lien vers un dossier : l'envoi est refusé. */
    DIRECTORY,

    /**
     * Une entrée qu'un envoi ne peut pas remplacer : lien cassé, lien en boucle ou dont la cible
     * est refusée au `stat` (le serveur ne sait pas ou ne veut pas la suivre), tube nommé (FIFO),
     * socket, périphérique. L'envoi est refusé tout de suite, avec un message qui le dit.
     */
    OTHER,
}
