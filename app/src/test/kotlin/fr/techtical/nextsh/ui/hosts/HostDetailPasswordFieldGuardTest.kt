// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.hosts

import fr.techtical.nextsh.ui.SourceGuardSupport
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Source-level guard for the host password field of `HostDetailScreen`.
 *
 * Until 1.0.1 the field was a plain `NextShTextField`: the password showed in clear, the keyboard
 * offered corrections and suggestions (and could learn the secret), and the value was exposed in
 * clear to accessibility services. A Compose UI test would need an instrumented run and there is no
 * `androidTest` source set, hence the same compromise as [fr.techtical.nextsh.data.db.dao.SyncUpsertGuardTest]:
 * a cheap check on the source of the `AuthType.PASSWORD` branch, with comments stripped so that a
 * commented-out call does not count.
 *
 * Unlike the DAO guard, a source that cannot be located FAILS the test instead of skipping it (see
 * [SourceGuardSupport.locate]). The other secret fields are covered by `SecretFieldsKeyboardGuardTest`.
 * It is deliberately strict on the shape of the code (it will need an update if
 * the branch is refactored): that is the point of a guard on a secret. `app/build.gradle.kts`
 * declares the `ui/` directory and both `strings.xml` as inputs of the Test task, so that a change
 * to them reruns this guard despite the Gradle build cache.
 */
class HostDetailPasswordFieldGuardTest {

    /** The body of the `AuthType.PASSWORD -> { ... }` branch of the auth section, comments removed. */
    private fun passwordBranch(): String {
        val source = SourceGuardSupport.mainSource("ui/hosts/HostDetailScreen.kt")
        val start = Regex("""AuthType\.PASSWORD\s*->\s*\{""").find(source)
        assertTrue(
            start != null,
            "HostDetailScreen.kt: the `AuthType.PASSWORD -> { ... }` branch of the auth section is gone",
        )
        val end = Regex("""AuthType\.SSH_KEY\s*->\s*\{""").find(source, start!!.range.last)
        assertTrue(
            end != null,
            "HostDetailScreen.kt: the `AuthType.SSH_KEY -> { ... }` branch that closes the password branch is gone",
        )
        return source.substring(start.range.last, end!!.range.first)
    }

    @Test
    fun `host password field is bound to the form password`() {
        assertTrue(
            Regex("""NextShTextField\(\s*value\s*=\s*form\.password\b""").containsMatchIn(passwordBranch()),
            "the AuthType.PASSWORD branch must hold the NextShTextField bound to form.password; " +
                "if it moved, update this guard so the new field is still checked",
        )
    }

    @Test
    fun `host password field is masked by default with PasswordVisualTransformation`() {
        val branch = passwordBranch()
        assertTrue(
            Regex("""PasswordVisualTransformation\(\)""").containsMatchIn(branch),
            "the host password field must use PasswordVisualTransformation(): without it the " +
                "password shows in clear and is exposed in clear to accessibility services",
        )
        assertTrue(
            Regex("""if\s*\(\s*passwordVisible\s*\)\s*\{\s*VisualTransformation\.None\s*\}\s*else\s*\{\s*PasswordVisualTransformation\(\)""")
                .containsMatchIn(branch),
            "the host password field must be masked unless the user turned the eye toggle on: " +
                "`if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation()`",
        )
        assertTrue(
            Regex("""var\s+passwordVisible\s+by\s+remember\s*\{\s*mutableStateOf\(\s*false\s*\)\s*\}""").containsMatchIn(branch),
            "the password visibility must start masked: `var passwordVisible by remember { mutableStateOf(false) }`",
        )
    }

    @Test
    fun `host password field uses the password keyboard without autocorrect`() {
        val branch = passwordBranch()
        assertTrue(
            Regex("""KeyboardType\.Password\b""").containsMatchIn(branch),
            "the host password field must use KeyboardType.Password: a normal text keyboard offers " +
                "suggestions and can learn the password",
        )
        assertTrue(
            // `autoCorrect` (Compose 1.6) or `autoCorrectEnabled` (Compose 1.7 and later).
            Regex("""autoCorrect(Enabled)?\s*=\s*false\b""").containsMatchIn(branch),
            "the host password field must disable autoCorrect so the keyboard does not learn the password",
        )
    }

    @Test
    fun `host password goes back to masked when the field loses focus`() {
        assertTrue(
            Regex("""\.onFocusChanged\s*\{\s*if\s*\(\s*!it\.isFocused\s*\)\s*passwordVisible\s*=\s*false\s*\}""")
                .containsMatchIn(passwordBranch()),
            "the host password field must hide the password again as soon as it loses focus: " +
                "`.onFocusChanged { if (!it.isFocused) passwordVisible = false }`",
        )
    }

    @Test
    fun `host password eye gives the field the focus when it reveals the password`() {
        val branch = passwordBranch()
        assertTrue(
            Regex("""\.focusRequester\(\s*passwordFocus\s*\)""").containsMatchIn(branch),
            "the host password field must carry `.focusRequester(passwordFocus)`",
        )
        assertTrue(
            Regex("""onClick\s*=\s*\{[^}]*passwordVisible\s*=\s*!passwordVisible[^}]*if\s*\(\s*passwordVisible\s*\)\s*passwordFocus\.requestFocus\(\)""")
                .containsMatchIn(branch),
            "revealing the password must give the field the focus (`if (passwordVisible) " +
                "passwordFocus.requestFocus()` in the eye click), otherwise the password would " +
                "stay visible outside the active field, where the focus-loss reset never fires",
        )
    }

    @Test
    fun `host password goes back to masked when the screen stops`() {
        val branch = passwordBranch()
        assertTrue(
            Regex("""DisposableEffect\(\s*lifecycleOwner\s*\)""").containsMatchIn(branch),
            "the password branch must observe the lifecycle with a DisposableEffect(lifecycleOwner): " +
                "Compose keeps the focus when the app goes to the background, so the focus-loss " +
                "reset alone leaves the password visible on return",
        )
        assertTrue(
            Regex("""LifecycleEventObserver\s*\{\s*_\s*,\s*event\s*->\s*if\s*\(\s*event\s*==\s*Lifecycle\.Event\.ON_STOP\s*\)\s*passwordVisible\s*=\s*false\s*\}""")
                .containsMatchIn(branch),
            "the observer must hide the password again on ON_STOP",
        )
        assertTrue(
            branch.contains("lifecycle.addObserver(observer)") && branch.contains("lifecycle.removeObserver(observer)"),
            "the observer must be added and removed (onDispose) so it does not leak",
        )
    }

    @Test
    fun `host password visibility never goes to a Bundle`() {
        assertFalse(
            Regex("""rememberSaveable""").containsMatchIn(passwordBranch()),
            "the host password field and its visibility state must use `remember`, never " +
                "`rememberSaveable`: a secret, or a state tied to it, must not end up in a Bundle",
        )
    }

    @Test
    fun `host password eye toggle has an accessible description in both states`() {
        val branch = passwordBranch()
        assertTrue(
            Regex("""R\.string\.action_show_password""").containsMatchIn(branch) &&
                Regex("""R\.string\.action_hide_password""").containsMatchIn(branch),
            "the eye toggle must describe both states with action_show_password and action_hide_password",
        )
    }

    private fun stringValues(pathFromModuleRoot: String): Map<String, String> =
        Regex("""<string\s+name="([^"]+)"[^>]*>([\s\S]*?)</string>""")
            .findAll(SourceGuardSupport.locate(pathFromModuleRoot).readText())
            .associate { it.groupValues[1] to it.groupValues[2].trim() }

    @Test
    fun `strings used by the host password field exist in French and English`() {
        val needed = listOf(
            "label_password",
            "placeholder_password",
            "action_show_password",
            "action_hide_password",
        )
        val french = stringValues("src/main/res/values/strings.xml")
        val english = stringValues("src/main/res/values-en/strings.xml")
        needed.forEach { name ->
            assertTrue(!french[name].isNullOrBlank(), "values/strings.xml: missing or empty string `$name`")
            assertTrue(!english[name].isNullOrBlank(), "values-en/strings.xml: missing or empty string `$name`")
        }
    }
}
