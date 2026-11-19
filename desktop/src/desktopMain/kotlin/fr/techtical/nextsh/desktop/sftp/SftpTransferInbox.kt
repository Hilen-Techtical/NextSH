// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sftp

import fr.techtical.nextsh.shared.domain.model.SortOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * Identifies one SFTP browser: the `TabContent.Sftp.paneId` of its pane. Not its tab, and
 * not its place in the tab: several browsers can live in one tab, and a place changes (or
 * is shared, by two panes of two different splits) as the tree of panes changes. The pane
 * id is minted once, when the pane is created, and follows the pane wherever it moves.
 */
data class SftpPaneKey(val paneId: String)

/**
 * How the list of one folder is shown in a pane: the hidden files and the sort order. [folder] is the
 * folder these options were chosen for: another folder starts again from the defaults.
 */
data class SftpViewOptions(val folder: String, val hiddenVisible: Boolean, val sortOrder: SortOrder)

/**
 * What an SFTP browser has to tell the user about its transfers, kept outside the browser
 * itself so that it outlives the screen. Singleton (via
 * [fr.techtical.nextsh.desktop.data.di.DesktopContainer]).
 *
 * The browser screen leaves the composition when the user opens another view (the
 * transfers list, for example) and its view model is created again on the way back:
 * anything kept in the view model would be lost silently. Two things are kept here, per
 * browser ([SftpPaneKey]):
 *
 * - a first-in first-out queue of [ReplacePrompt]s, the questions "replace this file?" that
 *   wait for the user's answer. The question on screen is the head of the queue; a later
 *   one waits behind it instead of taking its place, so an answer given to a question can
 *   never be applied to another one. [removeHead] only takes the head off if it is the
 *   question the caller answered;
 * - a notice: the last message that was not shown yet, either a refused transfer ("a folder
 *   of that name exists") or a failed operation on the files (create, rename, permissions,
 *   delete). A folder reload does not clear it, only [dismissNotice] does.
 *
 * Nothing is persisted: the inbox lives as long as the process, and the container empties
 * the browsers of a closed pane or tab ([retainPanes]).
 */
class SftpTransferInbox {

    private class Box {
        val prompts = MutableStateFlow<List<ReplacePrompt>>(emptyList())
        /** The notice on screen: the head of the queue of notices. */
        val notice = MutableStateFlow<String?>(null)

        /** The notices that wait behind [notice], oldest first. Guarded by `synchronized(this)`. */
        val waitingNotices = ArrayDeque<String>()

        /** The view options of the pane, kept for the browser that replaces this one (tab change). */
        val viewOptions = java.util.concurrent.atomic.AtomicReference<SftpViewOptions?>(null)

        /** Counts the reloads asked for the pane (see [SftpTransferInbox.requestReload]). */
        val reloadRequests = MutableStateFlow(0)

        /** The folders whose leftover banner was dismissed (see [SftpTransferInbox.dismissLeftoverBanner]). */
        val dismissedLeftoverFolders: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }

    // Boxes are never removed, only emptied: a view model keeps hold of the flows of its box,
    // and a box that went away and came back would leave it looking at flows nobody writes to.
    private val boxes = ConcurrentHashMap<SftpPaneKey, Box>()

    private fun box(key: SftpPaneKey): Box = boxes.getOrPut(key) { Box() }

    /** The questions waiting for [key], oldest first. The first one is the one to show. */
    fun prompts(key: SftpPaneKey): StateFlow<List<ReplacePrompt>> = box(key).prompts.asStateFlow()

    /** The refusal message waiting to be shown for [key], or null. */
    fun notice(key: SftpPaneKey): StateFlow<String?> = box(key).notice.asStateFlow()

    /** Puts [prompt] at the end of the queue of [key]. */
    fun enqueue(key: SftpPaneKey, prompt: ReplacePrompt) {
        box(key).prompts.update { it + prompt }
    }

    /**
     * Takes [prompt] off the queue of [key], if and only if it is the head of the queue
     * (compared by [ReplacePrompt.id]). Returns whether it did. A caller that gets false
     * must do nothing: the question was already answered, or is not the one being shown.
     * Atomic: of two simultaneous answers to the same question, only one gets true.
     */
    fun removeHead(key: SftpPaneKey, prompt: ReplacePrompt): Boolean {
        var removed = false
        box(key).prompts.update { queue ->
            removed = queue.firstOrNull()?.id == prompt.id
            if (removed) queue.drop(1) else queue
        }
        return removed
    }

    /**
     * Adds a message for [key] (a refused transfer or a failed operation on the files). The
     * notices form a small first-in first-out queue of at most [MAX_NOTICES]: the one on screen
     * is the head, and a message that arrives meanwhile waits behind it instead of erasing it
     * (an operation error and a refused transfer can happen together). A message equal to the
     * last one queued is not added twice; when the queue is full the newest waiting message is
     * replaced.
     */
    fun postNotice(key: SftpPaneKey, message: String) {
        val box = box(key)
        synchronized(box) {
            val shown = box.notice.value
            when {
                shown == null -> box.notice.value = message
                box.waitingNotices.isEmpty() && shown == message -> Unit
                box.waitingNotices.lastOrNull() == message -> Unit
                box.waitingNotices.size < MAX_NOTICES - 1 -> box.waitingNotices.addLast(message)
                else -> {
                    box.waitingNotices.removeLast()
                    box.waitingNotices.addLast(message)
                }
            }
        }
    }

    /** Dismisses the notice on screen: the next one, if any, takes its place. */
    fun dismissNotice(key: SftpPaneKey) {
        val box = boxes[key] ?: return
        synchronized(box) { box.notice.value = box.waitingNotices.removeFirstOrNull() }
    }

    /**
     * Dismisses the notice on screen only if it is still [shown]: a second click (or a repeated key) on
     * a button that showed an earlier notice must not take off the next one, which was not read yet.
     */
    fun dismissNotice(key: SftpPaneKey, shown: String) {
        val box = boxes[key] ?: return
        synchronized(box) {
            if (box.notice.value == shown) box.notice.value = box.waitingNotices.removeFirstOrNull()
        }
    }

    /**
     * Asks the browser of [key] to reload its folder (an upload of that pane ended). Kept here, and
     * not as a call on the view model that started the upload, because that view model may be gone
     * (tab change) and its successor must be the one that reloads: it collects [reloadRequests].
     */
    fun requestReload(key: SftpPaneKey) {
        box(key).reloadRequests.update { it + 1 }
    }

    /** The number of reloads asked for [key] so far; a browser reloads when it grows. */
    fun reloadRequests(key: SftpPaneKey): StateFlow<Int> = box(key).reloadRequests.asStateFlow()

    /** The view options last used in [key], or null. */
    fun viewOptions(key: SftpPaneKey): SftpViewOptions? = boxes[key]?.viewOptions?.get()

    /** Remembers the view options of [key], to be taken up again by the browser that replaces this one. */
    fun setViewOptions(key: SftpPaneKey, options: SftpViewOptions) {
        box(key).viewOptions.set(options)
    }

    /** Remembers that the leftover banner of [folder] was dismissed in [key], until the pane is closed. */
    fun dismissLeftoverBanner(key: SftpPaneKey, folder: String) {
        box(key).dismissedLeftoverFolders.add(folder)
    }

    /** Whether the leftover banner of [folder] was dismissed in [key]. */
    fun isLeftoverBannerDismissed(key: SftpPaneKey, folder: String): Boolean =
        boxes[key]?.dismissedLeftoverFolders?.contains(folder) == true

    /**
     * Empties the browsers that are not in [livePanes]: a closed pane (or a closed tab, which
     * closes its panes) takes its pending questions (which count as refused: nothing is sent,
     * nothing is replaced) and its notice with it. A pane that merely moved in the tree, as
     * the survivor of a closed sibling does, is still in [livePanes] and keeps both.
     */
    fun retainPanes(livePanes: Set<SftpPaneKey>) {
        for ((key, content) in boxes) {
            if (key in livePanes) continue
            if (content.prompts.value.isNotEmpty()) content.prompts.value = emptyList()
            synchronized(content) {
                content.waitingNotices.clear()
                if (content.notice.value != null) content.notice.value = null
            }
            content.dismissedLeftoverFolders.clear()
            content.viewOptions.set(null)
        }
    }

    companion object {
        /** Notices kept per browser: the one on screen and those waiting behind it. */
        const val MAX_NOTICES = 3
    }
}
