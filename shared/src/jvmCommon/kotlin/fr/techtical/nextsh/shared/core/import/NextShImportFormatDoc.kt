// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.`import`

/**
 * Canonical, copyable example of the native nextsh-hosts import format, shown verbatim in the
 * "file format" info dialogs on both platforms. Kept as a Kotlin constant (not a translated
 * string resource) so a translation can never break the JSON.
 */
const val NEXTSH_IMPORT_FORMAT_EXAMPLE: String = """{
  "format": "nextsh-hosts",
  "version": 1,
  "hosts": [
    {
      "hostname": "10.0.0.12",
      "label": "Prod Web 01",
      "port": 22,
      "username": "deploy",
      "group": "Production",
      "auth": "ssh_key",
      "privateKey": "-----BEGIN OPENSSH PRIVATE KEY-----\n...\n-----END OPENSSH PRIVATE KEY-----",
      "keyPassphrase": ""
    },
    {
      "hostname": "bastion.example.net",
      "port": 2222,
      "username": "admin",
      "auth": "password",
      "password": ""
    }
  ]
}"""
