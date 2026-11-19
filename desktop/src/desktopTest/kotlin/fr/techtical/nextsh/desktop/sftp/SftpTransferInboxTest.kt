// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sftp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SftpTransferInbox]: the queue of "replace this file?" questions and the refusal notice
 * of each SFTP browser. These rules are what keep an answer from being applied to a question
 * it was not given for, and a question from being lost when the screen is created again.
 */
class SftpTransferInboxTest {

    private val inbox = SftpTransferInbox()
    private val key = SftpPaneKey("pane-1")

    private fun prompt(name: String): ReplacePrompt =
        ReplacePrompt.Upload(File(name), "/srv", "/srv/$name", sessionId = "ssh-1", paneId = "pane-1")

    private fun ids(key: SftpPaneKey = this.key): List<String> = inbox.prompts(key).value.map { it.id }

    // ── The queue ─────────────────────────────────────────────────────────────

    @Test
    fun `a browser starts with no question and no notice`() {
        assertTrue(inbox.prompts(key).value.isEmpty())
        assertNull(inbox.notice(key).value)
    }

    @Test
    fun `questions come out in the order they went in`() {
        val first = prompt("a.txt")
        val second = prompt("b.txt")
        val third = prompt("c.txt")

        inbox.enqueue(key, first)
        inbox.enqueue(key, second)
        inbox.enqueue(key, third)

        assertEquals(listOf(first.id, second.id, third.id), ids())
    }

    @Test
    fun `only the head of the queue can be taken off`() {
        val first = prompt("a.txt")
        val second = prompt("b.txt")
        inbox.enqueue(key, first)
        inbox.enqueue(key, second)

        assertFalse(inbox.removeHead(key, second), "the second question is not the one on screen")
        assertEquals(listOf(first.id, second.id), ids())

        assertTrue(inbox.removeHead(key, first))
        assertEquals(listOf(second.id), ids())

        assertTrue(inbox.removeHead(key, second))
        assertTrue(ids().isEmpty())
    }

    @Test
    fun `a question already taken off cannot be taken off again`() {
        val only = prompt("a.txt")
        inbox.enqueue(key, only)

        assertTrue(inbox.removeHead(key, only))
        assertFalse(inbox.removeHead(key, only))
    }

    @Test
    fun `two questions about the same file are two questions`() {
        val first = prompt("a.txt")
        val second = prompt("a.txt")
        assertNotEquals(first.id, second.id)
        inbox.enqueue(key, first)
        inbox.enqueue(key, second)

        assertTrue(inbox.removeHead(key, first))

        // The answer given to the first one does not close the second, which is now on screen.
        assertFalse(inbox.removeHead(key, first))
        assertEquals(listOf(second.id), ids())
    }

    @Test
    fun `taking a question off an empty or unknown browser does nothing`() {
        assertFalse(inbox.removeHead(SftpPaneKey("never-seen"), prompt("a.txt")))
        assertFalse(inbox.removeHead(key, prompt("a.txt")))
    }

    @Test
    fun `of simultaneous answers to one question exactly one is accepted`() = runBlocking<Unit> {
        val only = prompt("a.txt")
        inbox.enqueue(key, only)

        val accepted = coroutineScope {
            List(64) { async(Dispatchers.Default) { inbox.removeHead(key, only) } }.awaitAll()
        }

        assertEquals(1, accepted.count { it })
        assertTrue(ids().isEmpty())
    }

    // ── One box per browser ───────────────────────────────────────────────────

    @Test
    fun `each browser has its own questions and its own notice`() {
        val paneOne = SftpPaneKey("pane-1")
        val paneTwo = SftpPaneKey("pane-2")
        val paneThree = SftpPaneKey("pane-3")
        val inOne = prompt("one.txt")
        val inTwo = prompt("two.txt")

        inbox.enqueue(paneOne, inOne)
        inbox.enqueue(paneTwo, inTwo)
        inbox.postNotice(paneOne, "one")

        assertEquals(listOf(inOne.id), ids(paneOne))
        assertEquals(listOf(inTwo.id), ids(paneTwo))
        assertTrue(ids(paneThree).isEmpty())
        assertEquals("one", inbox.notice(paneOne).value)
        assertNull(inbox.notice(paneTwo).value)
    }

    @Test
    fun `the flows handed out earlier follow the box, as a view model re-created later sees it`() {
        val before = inbox.prompts(key)
        val noticeBefore = inbox.notice(key)
        val queued = prompt("a.txt")

        inbox.enqueue(key, queued)
        inbox.postNotice(key, "message")

        assertEquals(listOf(queued.id), before.value.map { it.id })
        assertEquals("message", noticeBefore.value)
        assertEquals(listOf(queued.id), inbox.prompts(key).value.map { it.id })
        assertEquals("message", inbox.notice(key).value)
    }

    // ── The notice ────────────────────────────────────────────────────────────

    @Test
    fun `a notice stays until it is dismissed and a newer one waits behind it`() {
        inbox.postNotice(key, "first")
        assertEquals("first", inbox.notice(key).value)

        inbox.postNotice(key, "second")
        assertEquals("first", inbox.notice(key).value)

        inbox.dismissNotice(key)
        assertEquals("second", inbox.notice(key).value)

        inbox.dismissNotice(key)
        assertNull(inbox.notice(key).value)
    }

    @Test
    fun `at most three notices are kept, the newest waiting one is replaced when the queue is full`() {
        for (message in listOf("a", "b", "c", "d", "e")) inbox.postNotice(key, message)

        val shown = mutableListOf<String?>()
        repeat(4) {
            shown += inbox.notice(key).value
            inbox.dismissNotice(key)
        }

        assertEquals(listOf("a", "b", "e", null), shown)
    }

    @Test
    fun `a message equal to the one on screen or to the last one waiting is not added twice`() {
        inbox.postNotice(key, "same")
        inbox.postNotice(key, "same")
        inbox.postNotice(key, "other")
        inbox.postNotice(key, "other")

        inbox.dismissNotice(key)
        assertEquals("other", inbox.notice(key).value)
        inbox.dismissNotice(key)
        assertNull(inbox.notice(key).value)
    }

    @Test
    fun `closing a pane empties its waiting notices too`() {
        inbox.postNotice(key, "one")
        inbox.postNotice(key, "two")

        inbox.retainPanes(emptySet())

        assertNull(inbox.notice(key).value)
        inbox.postNotice(key, "fresh")
        inbox.dismissNotice(key)
        assertNull(inbox.notice(key).value)
    }

    @Test
    fun `a notice is dismissed by the text it was shown with, a repeated dismissal leaves the next one`() {
        inbox.postNotice(key, "first")
        inbox.postNotice(key, "second")

        inbox.dismissNotice(key, "first")
        inbox.dismissNotice(key, "first")

        assertEquals("second", inbox.notice(key).value)
        inbox.dismissNotice(key, "second")
        assertNull(inbox.notice(key).value)
    }

    // ── The reload requests ───────────────────────────────────────────────────

    @Test
    fun `every reload request raises the count of its pane only`() {
        val other = SftpPaneKey("pane-other")
        val before = inbox.reloadRequests(key).value

        inbox.requestReload(key)
        inbox.requestReload(key)

        assertEquals(before + 2, inbox.reloadRequests(key).value)
        assertEquals(0, inbox.reloadRequests(other).value)
    }

    // ── The dismissed leftover banners ────────────────────────────────────────

    @Test
    fun `a dismissed leftover banner is remembered per pane and per folder`() {
        val other = SftpPaneKey("pane-other")
        assertFalse(inbox.isLeftoverBannerDismissed(key, "/srv"))

        inbox.dismissLeftoverBanner(key, "/srv")

        assertTrue(inbox.isLeftoverBannerDismissed(key, "/srv"))
        assertFalse(inbox.isLeftoverBannerDismissed(key, "/srv/sub"))
        assertFalse(inbox.isLeftoverBannerDismissed(other, "/srv"))
    }

    @Test
    fun `a dismissed leftover banner is forgotten when its pane is closed and kept when it only moved`() {
        val other = SftpPaneKey("pane-other")
        inbox.dismissLeftoverBanner(key, "/srv")
        inbox.dismissLeftoverBanner(other, "/srv")

        inbox.retainPanes(setOf(other))

        assertFalse(inbox.isLeftoverBannerDismissed(key, "/srv"))
        assertTrue(inbox.isLeftoverBannerDismissed(other, "/srv"))
    }

    @Test
    fun `dismissing the notice of an unknown browser does nothing`() {
        inbox.dismissNotice(SftpPaneKey("never-seen"))
    }

    @Test
    fun `the notice and the questions do not interfere`() {
        val queued = prompt("a.txt")
        inbox.enqueue(key, queued)
        inbox.postNotice(key, "message")

        inbox.dismissNotice(key)
        assertEquals(listOf(queued.id), ids())

        inbox.postNotice(key, "again")
        inbox.removeHead(key, queued)
        assertEquals("again", inbox.notice(key).value)
    }

    // ── Closed panes ──────────────────────────────────────────────────────────

    @Test
    fun `a closed pane loses its questions and its notice, the panes still open keep theirs`() {
        val closed = SftpPaneKey("closed-pane")
        val open = SftpPaneKey("open-pane")
        val kept = prompt("kept.txt")
        inbox.enqueue(closed, prompt("a.txt"))
        inbox.postNotice(closed, "gone")
        inbox.enqueue(open, kept)
        inbox.postNotice(open, "still here")

        inbox.retainPanes(setOf(open))

        assertTrue(ids(closed).isEmpty())
        assertNull(inbox.notice(closed).value)
        assertEquals(listOf(kept.id), ids(open))
        assertEquals("still here", inbox.notice(open).value)
    }

    @Test
    fun `closing one pane of a tab leaves the other panes of that tab alone`() {
        // The panes of one tab are told apart by their ids, whatever tab or slot they are in.
        val closedPane = SftpPaneKey("pane-x")
        val sibling = SftpPaneKey("pane-y")
        val others = SftpPaneKey("pane-z")
        val kept = prompt("kept.txt")
        inbox.enqueue(closedPane, prompt("a.txt"))
        inbox.enqueue(sibling, kept)
        inbox.enqueue(others, prompt("c.txt"))

        inbox.retainPanes(setOf(sibling, others))

        assertTrue(ids(closedPane).isEmpty())
        assertEquals(listOf(kept.id), ids(sibling))
        assertEquals(1, ids(others).size)
    }

    @Test
    fun `a pane that moved in the tree is still live and keeps its questions`() {
        // The tree is walked again after a sibling was closed: the survivor has a new place
        // and the same pane id, so the set of live panes still holds it.
        val survivor = SftpPaneKey("survivor")
        val question = prompt("a.txt")
        inbox.enqueue(survivor, question)

        inbox.retainPanes(setOf(survivor))
        inbox.retainPanes(setOf(survivor))

        assertEquals(listOf(question.id), ids(survivor))
    }

    @Test
    fun `emptying a closed pane keeps its flows usable`() {
        val closed = SftpPaneKey("closed-pane")
        val flow = inbox.prompts(closed)
        inbox.enqueue(closed, prompt("a.txt"))

        inbox.retainPanes(emptySet())
        val later = prompt("b.txt")
        inbox.enqueue(closed, later)

        assertEquals(listOf(later.id), flow.value.map { it.id })
    }
}
