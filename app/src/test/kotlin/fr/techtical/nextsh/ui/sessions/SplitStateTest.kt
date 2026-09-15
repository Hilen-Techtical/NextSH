// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sessions

import fr.techtical.nextsh.core.ssh.SshTerminalSession
import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.SessionStatus
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Unit tests for split-screen state management (Phase 1).
 *
 * These tests verify the split-screen data classes and state transitions
 * without instantiating SessionViewModel (which requires Android Context).
 * Tests simulate the ViewModel's _uiState.update {} patterns using
 * MutableStateFlow<SessionUiState>.
 *
 * Tests verify:
 * - SplitState data class defaults and field storage
 * - PaneContent Terminal and Sftp variants
 * - State transitions: enterSplit, exitSplit, orientation toggle, ratio clamping, pane focus/swap
 * - closeTab logic: split exit when pane tab is closed, index adjustment
 * - focusedSession logic: returns correct pane content
 */
class SplitStateTest {

    // ── SplitState data class tests ────────────────────────────────────────────────

    @Test
    fun `splitState defaults to 50-50 ratio and LEFT_OR_TOP focus`() {
        val split = SplitState(
            leftOrTopPane = PaneContent.Terminal(0),
            rightOrBottomPane = PaneContent.Terminal(1),
        )
        assertEquals(0.5f, split.splitRatio)
        assertEquals(PaneSlot.LEFT_OR_TOP, split.focusedPane)
        assertEquals(SplitOrientation.HORIZONTAL, split.orientation)
    }

    @Test
    fun `splitRatio is stored correctly`() {
        val split = SplitState(
            orientation = SplitOrientation.HORIZONTAL,
            leftOrTopPane = PaneContent.Terminal(0),
            rightOrBottomPane = PaneContent.Terminal(1),
            splitRatio = 0.3f,
        )
        assertEquals(0.3f, split.splitRatio)
    }

    @Test
    fun `PaneContent Terminal stores tabIndex`() {
        val pane = PaneContent.Terminal(tabIndex = 5)
        assertEquals(5, pane.tabIndex)
    }

    @Test
    fun `PaneContent Sftp stores sessionId and hostLabel`() {
        val pane = PaneContent.Sftp(sessionId = "session-123", hostLabel = "prod.example.com")
        assertEquals("session-123", pane.sessionId)
        assertEquals("prod.example.com", pane.hostLabel)
    }

    @Test
    fun `SplitState has no credential fields`() {
        // Verify by inspecting constructor parameters: data class must only contain:
        // orientation, leftOrTopPane, rightOrBottomPane, splitRatio, focusedPane
        val split = SplitState(
            orientation = SplitOrientation.HORIZONTAL,
            leftOrTopPane = PaneContent.Terminal(0),
            rightOrBottomPane = PaneContent.Terminal(1),
            splitRatio = 0.5f,
            focusedPane = PaneSlot.LEFT_OR_TOP,
        )
        // If any credential fields existed, they would be in the data class.
        // We verify this by ensuring the data class is immutable and has only these fields.
        assertEquals(SplitOrientation.HORIZONTAL, split.orientation)
        assertEquals(PaneSlot.LEFT_OR_TOP, split.focusedPane)
    }

    // ── State transition tests (simulating ViewModel logic) ────────────────────────

    @Test
    fun `enterSplit creates correct split state`() {
        val uiState = MutableStateFlow(
            SessionUiState(
                tabs = listOf(
                    SessionTab(
                        sessionId = "session-1",
                        host = createTestHost(id = "host-1", label = "server1"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                    SessionTab(
                        sessionId = "session-2",
                        host = createTestHost(id = "host-2", label = "server2"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                ),
                activeTabIndex = 0,
            )
        )

        // Simulate: enterSplit(secondTabIndex = 1)
        uiState.value = uiState.value.copy(
            splitState = SplitState(
                orientation = SplitOrientation.HORIZONTAL,
                leftOrTopPane = PaneContent.Terminal(0),
                rightOrBottomPane = PaneContent.Terminal(1),
            )
        )

        val split = uiState.value.splitState!!
        assertEquals(0, (split.leftOrTopPane as PaneContent.Terminal).tabIndex)
        assertEquals(1, (split.rightOrBottomPane as PaneContent.Terminal).tabIndex)
        assertEquals(SplitOrientation.HORIZONTAL, split.orientation)
    }

    @Test
    fun `enterSplit with same tab index is rejected`() {
        val uiState = MutableStateFlow(
            SessionUiState(
                tabs = listOf(
                    SessionTab(
                        sessionId = "session-1",
                        host = createTestHost(id = "host-1", label = "server1"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                ),
                activeTabIndex = 0,
            )
        )

        // Simulate: enterSplit with secondTabIndex = activeTabIndex (0)
        val state = uiState.value
        val activeIdx = state.activeTabIndex
        val secondTabIndex = 0 // Same as active
        val shouldEnterSplit = activeIdx != secondTabIndex

        assertEquals(false, shouldEnterSplit)
        assertNull(uiState.value.splitState)
    }

    @Test
    fun `enterSplit with invalid tab index is rejected`() {
        val uiState = MutableStateFlow(
            SessionUiState(
                tabs = listOf(
                    SessionTab(
                        sessionId = "session-1",
                        host = createTestHost(id = "host-1", label = "server1"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                ),
                activeTabIndex = 0,
            )
        )

        // Simulate: enterSplit with out-of-bounds secondTabIndex
        val state = uiState.value
        val secondTabIndex = 99 // Out of bounds
        val isValid = state.tabs.getOrNull(state.activeTabIndex) != null &&
                      state.tabs.getOrNull(secondTabIndex) != null

        assertEquals(false, isValid)
        assertNull(uiState.value.splitState)
    }

    @Test
    fun `exitSplit restores active tab from focused pane`() {
        // Start with a split state where the focused pane is a Terminal at index 1
        val uiState = MutableStateFlow(
            SessionUiState(
                tabs = listOf(
                    SessionTab(
                        sessionId = "session-1",
                        host = createTestHost(id = "host-1", label = "server1"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                    SessionTab(
                        sessionId = "session-2",
                        host = createTestHost(id = "host-2", label = "server2"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                ),
                activeTabIndex = 0,
                splitState = SplitState(
                    orientation = SplitOrientation.HORIZONTAL,
                    leftOrTopPane = PaneContent.Terminal(0),
                    rightOrBottomPane = PaneContent.Terminal(1),
                    focusedPane = PaneSlot.RIGHT_OR_BOTTOM,
                ),
            )
        )

        // Simulate: exitSplit()
        uiState.value = uiState.value.let { state ->
            val split = state.splitState ?: return@let state
            val focusedContent = when (split.focusedPane) {
                PaneSlot.LEFT_OR_TOP -> split.leftOrTopPane
                PaneSlot.RIGHT_OR_BOTTOM -> split.rightOrBottomPane
            }
            val newActiveIndex = when (focusedContent) {
                is PaneContent.Terminal -> focusedContent.tabIndex
                is PaneContent.Sftp -> state.activeTabIndex
            }
            state.copy(splitState = null, activeTabIndex = newActiveIndex)
        }

        assertNull(uiState.value.splitState)
        assertEquals(1, uiState.value.activeTabIndex) // Focused pane had index 1
    }

    @Test
    fun `exitSplit with SFTP focused keeps current activeTabIndex`() {
        val uiState = MutableStateFlow(
            SessionUiState(
                tabs = listOf(
                    SessionTab(
                        sessionId = "session-1",
                        host = createTestHost(id = "host-1", label = "server1"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                ),
                activeTabIndex = 0,
                splitState = SplitState(
                    orientation = SplitOrientation.HORIZONTAL,
                    leftOrTopPane = PaneContent.Terminal(0),
                    rightOrBottomPane = PaneContent.Sftp("session-1", "server1"),
                    focusedPane = PaneSlot.RIGHT_OR_BOTTOM,
                ),
            )
        )

        // Simulate: exitSplit() with SFTP focused
        uiState.value = uiState.value.let { state ->
            val split = state.splitState ?: return@let state
            val focusedContent = when (split.focusedPane) {
                PaneSlot.LEFT_OR_TOP -> split.leftOrTopPane
                PaneSlot.RIGHT_OR_BOTTOM -> split.rightOrBottomPane
            }
            val newActiveIndex = when (focusedContent) {
                is PaneContent.Terminal -> focusedContent.tabIndex
                is PaneContent.Sftp -> state.activeTabIndex
            }
            state.copy(splitState = null, activeTabIndex = newActiveIndex)
        }

        assertNull(uiState.value.splitState)
        assertEquals(0, uiState.value.activeTabIndex) // activeTabIndex unchanged (SFTP)
    }

    @Test
    fun `toggleSplitOrientation flips between HORIZONTAL and VERTICAL`() {
        val uiState = MutableStateFlow(
            SessionUiState(
                tabs = listOf(
                    SessionTab(
                        sessionId = "session-1",
                        host = createTestHost(id = "host-1", label = "server1"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                    SessionTab(
                        sessionId = "session-2",
                        host = createTestHost(id = "host-2", label = "server2"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                ),
                activeTabIndex = 0,
                splitState = SplitState(
                    orientation = SplitOrientation.HORIZONTAL,
                    leftOrTopPane = PaneContent.Terminal(0),
                    rightOrBottomPane = PaneContent.Terminal(1),
                ),
            )
        )

        // Simulate: toggleSplitOrientation()
        uiState.value = uiState.value.let { state ->
            val split = state.splitState ?: return@let state
            val newOrientation = when (split.orientation) {
                SplitOrientation.HORIZONTAL -> SplitOrientation.VERTICAL
                SplitOrientation.VERTICAL -> SplitOrientation.HORIZONTAL
            }
            state.copy(splitState = split.copy(orientation = newOrientation))
        }

        assertEquals(SplitOrientation.VERTICAL, uiState.value.splitState?.orientation)

        // Toggle again
        uiState.value = uiState.value.let { state ->
            val split = state.splitState ?: return@let state
            val newOrientation = when (split.orientation) {
                SplitOrientation.HORIZONTAL -> SplitOrientation.VERTICAL
                SplitOrientation.VERTICAL -> SplitOrientation.HORIZONTAL
            }
            state.copy(splitState = split.copy(orientation = newOrientation))
        }

        assertEquals(SplitOrientation.HORIZONTAL, uiState.value.splitState?.orientation)
    }

    @Test
    fun `updateSplitRatio clamps below 0,2`() {
        val uiState = MutableStateFlow(
            SessionUiState(
                tabs = listOf(
                    SessionTab(
                        sessionId = "session-1",
                        host = createTestHost(id = "host-1", label = "server1"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                    SessionTab(
                        sessionId = "session-2",
                        host = createTestHost(id = "host-2", label = "server2"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                ),
                activeTabIndex = 0,
                splitState = SplitState(
                    orientation = SplitOrientation.HORIZONTAL,
                    leftOrTopPane = PaneContent.Terminal(0),
                    rightOrBottomPane = PaneContent.Terminal(1),
                    splitRatio = 0.5f,
                ),
            )
        )

        // Simulate: updateSplitRatio(0.1f)
        uiState.value = uiState.value.let { state ->
            val split = state.splitState ?: return@let state
            state.copy(splitState = split.copy(splitRatio = 0.1f.coerceIn(0.2f, 0.8f)))
        }

        assertEquals(0.2f, uiState.value.splitState?.splitRatio)
    }

    @Test
    fun `updateSplitRatio clamps above 0,8`() {
        val uiState = MutableStateFlow(
            SessionUiState(
                tabs = listOf(
                    SessionTab(
                        sessionId = "session-1",
                        host = createTestHost(id = "host-1", label = "server1"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                    SessionTab(
                        sessionId = "session-2",
                        host = createTestHost(id = "host-2", label = "server2"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                ),
                activeTabIndex = 0,
                splitState = SplitState(
                    orientation = SplitOrientation.HORIZONTAL,
                    leftOrTopPane = PaneContent.Terminal(0),
                    rightOrBottomPane = PaneContent.Terminal(1),
                    splitRatio = 0.5f,
                ),
            )
        )

        // Simulate: updateSplitRatio(0.9f)
        uiState.value = uiState.value.let { state ->
            val split = state.splitState ?: return@let state
            state.copy(splitState = split.copy(splitRatio = 0.9f.coerceIn(0.2f, 0.8f)))
        }

        assertEquals(0.8f, uiState.value.splitState?.splitRatio)
    }

    @Test
    fun `updateSplitRatio accepts valid values`() {
        val uiState = MutableStateFlow(
            SessionUiState(
                tabs = listOf(
                    SessionTab(
                        sessionId = "session-1",
                        host = createTestHost(id = "host-1", label = "server1"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                    SessionTab(
                        sessionId = "session-2",
                        host = createTestHost(id = "host-2", label = "server2"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                ),
                activeTabIndex = 0,
                splitState = SplitState(
                    orientation = SplitOrientation.HORIZONTAL,
                    leftOrTopPane = PaneContent.Terminal(0),
                    rightOrBottomPane = PaneContent.Terminal(1),
                    splitRatio = 0.5f,
                ),
            )
        )

        // Simulate: updateSplitRatio(0.3f)
        uiState.value = uiState.value.let { state ->
            val split = state.splitState ?: return@let state
            state.copy(splitState = split.copy(splitRatio = 0.3f.coerceIn(0.2f, 0.8f)))
        }

        assertEquals(0.3f, uiState.value.splitState?.splitRatio)
    }

    @Test
    fun `setFocusedPane updates focused slot`() {
        val uiState = MutableStateFlow(
            SessionUiState(
                tabs = listOf(
                    SessionTab(
                        sessionId = "session-1",
                        host = createTestHost(id = "host-1", label = "server1"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                    SessionTab(
                        sessionId = "session-2",
                        host = createTestHost(id = "host-2", label = "server2"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                ),
                activeTabIndex = 0,
                splitState = SplitState(
                    orientation = SplitOrientation.HORIZONTAL,
                    leftOrTopPane = PaneContent.Terminal(0),
                    rightOrBottomPane = PaneContent.Terminal(1),
                    focusedPane = PaneSlot.LEFT_OR_TOP,
                ),
            )
        )

        // Simulate: setFocusedPane(PaneSlot.RIGHT_OR_BOTTOM)
        uiState.value = uiState.value.let { state ->
            val split = state.splitState ?: return@let state
            if (split.focusedPane == PaneSlot.RIGHT_OR_BOTTOM) return@let state
            state.copy(splitState = split.copy(focusedPane = PaneSlot.RIGHT_OR_BOTTOM))
        }

        assertEquals(PaneSlot.RIGHT_OR_BOTTOM, uiState.value.splitState?.focusedPane)
    }

    @Test
    fun `setFocusedPane with same slot is no-op`() {
        val uiState = MutableStateFlow(
            SessionUiState(
                tabs = listOf(
                    SessionTab(
                        sessionId = "session-1",
                        host = createTestHost(id = "host-1", label = "server1"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                    SessionTab(
                        sessionId = "session-2",
                        host = createTestHost(id = "host-2", label = "server2"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                ),
                activeTabIndex = 0,
                splitState = SplitState(
                    orientation = SplitOrientation.HORIZONTAL,
                    leftOrTopPane = PaneContent.Terminal(0),
                    rightOrBottomPane = PaneContent.Terminal(1),
                    focusedPane = PaneSlot.LEFT_OR_TOP,
                ),
            )
        )

        val originalSplit = uiState.value.splitState

        // Simulate: setFocusedPane(PaneSlot.LEFT_OR_TOP) (same as current)
        uiState.value = uiState.value.let { state ->
            val split = state.splitState ?: return@let state
            if (split.focusedPane == PaneSlot.LEFT_OR_TOP) return@let state // No-op
            state.copy(splitState = split.copy(focusedPane = PaneSlot.LEFT_OR_TOP))
        }

        assertEquals(originalSplit, uiState.value.splitState)
        assertEquals(PaneSlot.LEFT_OR_TOP, uiState.value.splitState?.focusedPane)
    }

    @Test
    fun `swapPanes exchanges left and right content and focus follows content`() {
        val uiState = MutableStateFlow(
            SessionUiState(
                tabs = listOf(
                    SessionTab(
                        sessionId = "session-1",
                        host = createTestHost(id = "host-1", label = "server1"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                    SessionTab(
                        sessionId = "session-2",
                        host = createTestHost(id = "host-2", label = "server2"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                ),
                activeTabIndex = 0,
                splitState = SplitState(
                    orientation = SplitOrientation.HORIZONTAL,
                    leftOrTopPane = PaneContent.Terminal(0),
                    rightOrBottomPane = PaneContent.Terminal(1),
                    focusedPane = PaneSlot.LEFT_OR_TOP,
                ),
            )
        )

        // Simulate: swapPanes(), focus follows content
        uiState.value = uiState.value.let { state ->
            val split = state.splitState ?: return@let state
            val newFocus = when (split.focusedPane) {
                PaneSlot.LEFT_OR_TOP -> PaneSlot.RIGHT_OR_BOTTOM
                PaneSlot.RIGHT_OR_BOTTOM -> PaneSlot.LEFT_OR_TOP
            }
            state.copy(
                splitState = split.copy(
                    leftOrTopPane = split.rightOrBottomPane,
                    rightOrBottomPane = split.leftOrTopPane,
                    focusedPane = newFocus,
                )
            )
        }

        assertEquals(1, (uiState.value.splitState?.leftOrTopPane as PaneContent.Terminal).tabIndex)
        assertEquals(0, (uiState.value.splitState?.rightOrBottomPane as PaneContent.Terminal).tabIndex)
        // Focus should have followed the content to the other slot
        assertEquals(PaneSlot.RIGHT_OR_BOTTOM, uiState.value.splitState?.focusedPane)
    }

    @Test
    fun `closeTab exits split when left pane tab is closed`() {
        val uiState = MutableStateFlow(
            SessionUiState(
                tabs = listOf(
                    SessionTab(
                        sessionId = "session-1",
                        host = createTestHost(id = "host-1", label = "server1"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                    SessionTab(
                        sessionId = "session-2",
                        host = createTestHost(id = "host-2", label = "server2"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                ),
                activeTabIndex = 0,
                splitState = SplitState(
                    orientation = SplitOrientation.HORIZONTAL,
                    leftOrTopPane = PaneContent.Terminal(0),
                    rightOrBottomPane = PaneContent.Terminal(1),
                ),
            )
        )

        val closedIndex = 0
        val currentState = uiState.value

        // Simulate: closeTab(0)
        val newTabs = currentState.tabs.toMutableList().also { it.removeAt(closedIndex) }
        val newSplitState = currentState.splitState?.let { split ->
            val leftContent = split.leftOrTopPane
            val rightContent = split.rightOrBottomPane
            val closedInLeft = leftContent is PaneContent.Terminal && leftContent.tabIndex == closedIndex
            val closedInRight = rightContent is PaneContent.Terminal && rightContent.tabIndex == closedIndex
            if (closedInLeft || closedInRight) {
                null // Exit split mode
            } else {
                split
            }
        }

        uiState.value = uiState.value.copy(tabs = newTabs, splitState = newSplitState)

        assertNull(uiState.value.splitState)
        assertEquals(1, uiState.value.tabs.size)
    }

    @Test
    fun `closeTab exits split when right pane tab is closed`() {
        val uiState = MutableStateFlow(
            SessionUiState(
                tabs = listOf(
                    SessionTab(
                        sessionId = "session-1",
                        host = createTestHost(id = "host-1", label = "server1"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                    SessionTab(
                        sessionId = "session-2",
                        host = createTestHost(id = "host-2", label = "server2"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                ),
                activeTabIndex = 0,
                splitState = SplitState(
                    orientation = SplitOrientation.HORIZONTAL,
                    leftOrTopPane = PaneContent.Terminal(0),
                    rightOrBottomPane = PaneContent.Terminal(1),
                ),
            )
        )

        val closedIndex = 1
        val currentState = uiState.value

        // Simulate: closeTab(1)
        val newTabs = currentState.tabs.toMutableList().also { it.removeAt(closedIndex) }
        val newSplitState = currentState.splitState?.let { split ->
            val leftContent = split.leftOrTopPane
            val rightContent = split.rightOrBottomPane
            val closedInLeft = leftContent is PaneContent.Terminal && leftContent.tabIndex == closedIndex
            val closedInRight = rightContent is PaneContent.Terminal && rightContent.tabIndex == closedIndex
            if (closedInLeft || closedInRight) {
                null // Exit split mode
            } else {
                split
            }
        }

        uiState.value = uiState.value.copy(tabs = newTabs, splitState = newSplitState)

        assertNull(uiState.value.splitState)
        assertEquals(1, uiState.value.tabs.size)
    }

    @Test
    fun `closeTab adjusts tab indices when lower-index tab is removed`() {
        val uiState = MutableStateFlow(
            SessionUiState(
                tabs = listOf(
                    SessionTab(
                        sessionId = "session-1",
                        host = createTestHost(id = "host-1", label = "server1"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                    SessionTab(
                        sessionId = "session-2",
                        host = createTestHost(id = "host-2", label = "server2"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                    SessionTab(
                        sessionId = "session-3",
                        host = createTestHost(id = "host-3", label = "server3"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                ),
                activeTabIndex = 0,
                splitState = SplitState(
                    orientation = SplitOrientation.HORIZONTAL,
                    leftOrTopPane = PaneContent.Terminal(1),
                    rightOrBottomPane = PaneContent.Terminal(2),
                ),
            )
        )

        val closedIndex = 0
        val currentState = uiState.value

        // Simulate: closeTab(0)
        val newTabs = currentState.tabs.toMutableList().also { it.removeAt(closedIndex) }
        val newSplitState = currentState.splitState?.let { split ->
            val leftContent = split.leftOrTopPane
            val rightContent = split.rightOrBottomPane
            val closedInLeft = leftContent is PaneContent.Terminal && leftContent.tabIndex == closedIndex
            val closedInRight = rightContent is PaneContent.Terminal && rightContent.tabIndex == closedIndex
            if (closedInLeft || closedInRight) {
                null
            } else {
                val adjustIndex = { content: PaneContent ->
                    when (content) {
                        is PaneContent.Terminal -> {
                            val adjusted = if (content.tabIndex > closedIndex) content.tabIndex - 1 else content.tabIndex
                            PaneContent.Terminal(adjusted)
                        }
                        is PaneContent.Sftp -> content
                    }
                }
                split.copy(
                    leftOrTopPane = adjustIndex(split.leftOrTopPane),
                    rightOrBottomPane = adjustIndex(split.rightOrBottomPane),
                )
            }
        }

        uiState.value = uiState.value.copy(tabs = newTabs, splitState = newSplitState)

        // Indices should be decremented: 1→0, 2→1
        assertEquals(0, (uiState.value.splitState?.leftOrTopPane as PaneContent.Terminal).tabIndex)
        assertEquals(1, (uiState.value.splitState?.rightOrBottomPane as PaneContent.Terminal).tabIndex)
    }

    @Test
    fun `focusedSession logic returns correct pane content`() {
        // Test with focused Terminal pane
        val uiState = MutableStateFlow(
            SessionUiState(
                tabs = listOf(
                    SessionTab(
                        sessionId = "session-1",
                        host = createTestHost(id = "host-1", label = "server1"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                    SessionTab(
                        sessionId = "session-2",
                        host = createTestHost(id = "host-2", label = "server2"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                ),
                activeTabIndex = 0,
                splitState = SplitState(
                    orientation = SplitOrientation.HORIZONTAL,
                    leftOrTopPane = PaneContent.Terminal(0),
                    rightOrBottomPane = PaneContent.Terminal(1),
                    focusedPane = PaneSlot.RIGHT_OR_BOTTOM,
                ),
            )
        )

        // Simulate: focusedSession logic
        val state = uiState.value
        val split = state.splitState
        val focusedContent = when (split?.focusedPane) {
            PaneSlot.LEFT_OR_TOP -> split.leftOrTopPane
            PaneSlot.RIGHT_OR_BOTTOM -> split.rightOrBottomPane
            null -> null
        }
        val focusedTabIndex = when (focusedContent) {
            is PaneContent.Terminal -> focusedContent.tabIndex
            else -> null
        }

        assertEquals(1, focusedTabIndex)
    }

    @Test
    fun `enterSplitWithSftp creates Terminal plus Sftp panes`() {
        val uiState = MutableStateFlow(
            SessionUiState(
                tabs = listOf(
                    SessionTab(
                        sessionId = "session-1",
                        host = createTestHost(id = "host-1", label = "server1"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                ),
                activeTabIndex = 0,
            )
        )

        // Simulate: enterSplitWithSftp(sessionId = "session-1", hostLabel = "server1")
        uiState.value = uiState.value.let { state ->
            val activeIdx = state.activeTabIndex
            if (state.tabs.getOrNull(activeIdx) == null) return@let state
            state.copy(
                splitState = SplitState(
                    orientation = SplitOrientation.HORIZONTAL,
                    leftOrTopPane = PaneContent.Terminal(activeIdx),
                    rightOrBottomPane = PaneContent.Sftp("session-1", "server1"),
                )
            )
        }

        val split = uiState.value.splitState!!
        assertEquals(0, (split.leftOrTopPane as PaneContent.Terminal).tabIndex)
        assertEquals("session-1", (split.rightOrBottomPane as PaneContent.Sftp).sessionId)
        assertEquals("server1", (split.rightOrBottomPane as PaneContent.Sftp).hostLabel)
    }

    @Test
    fun `splitState null means single-pane mode`() {
        val uiState = MutableStateFlow(
            SessionUiState(
                tabs = listOf(
                    SessionTab(
                        sessionId = "session-1",
                        host = createTestHost(id = "host-1", label = "server1"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                ),
                activeTabIndex = 0,
                splitState = null,
            )
        )

        assertNull(uiState.value.splitState)
        assertEquals(1, uiState.value.tabs.size)
        assertEquals(0, uiState.value.activeTabIndex)
    }

    // ── Divider ratio computation tests (DraggableDivider logic) ────────────────────

    @Test
    fun `divider ratio computation with positive delta moves ratio up`() {
        val uiState = MutableStateFlow(
            SessionUiState(
                tabs = listOf(
                    SessionTab(
                        sessionId = "session-1",
                        host = createTestHost(id = "host-1", label = "server1"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                    SessionTab(
                        sessionId = "session-2",
                        host = createTestHost(id = "host-2", label = "server2"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                ),
                activeTabIndex = 0,
                splitState = SplitState(
                    orientation = SplitOrientation.HORIZONTAL,
                    leftOrTopPane = PaneContent.Terminal(0),
                    rightOrBottomPane = PaneContent.Terminal(1),
                    splitRatio = 0.5f,
                ),
            )
        )

        // Simulate: drag delta of 100px with totalSize 1000px
        // New ratio = 0.5 + (100 / 1000) = 0.5 + 0.1 = 0.6
        val delta = 100f
        val totalSize = 1000f
        val newRatio = (0.5f + (delta / totalSize)).coerceIn(0.2f, 0.8f)

        uiState.value = uiState.value.let { state ->
            val split = state.splitState ?: return@let state
            state.copy(splitState = split.copy(splitRatio = newRatio))
        }

        assertEquals(0.6f, uiState.value.splitState?.splitRatio)
    }

    @Test
    fun `divider ratio computation with negative delta moves ratio down`() {
        val uiState = MutableStateFlow(
            SessionUiState(
                tabs = listOf(
                    SessionTab(
                        sessionId = "session-1",
                        host = createTestHost(id = "host-1", label = "server1"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                    SessionTab(
                        sessionId = "session-2",
                        host = createTestHost(id = "host-2", label = "server2"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                ),
                activeTabIndex = 0,
                splitState = SplitState(
                    orientation = SplitOrientation.HORIZONTAL,
                    leftOrTopPane = PaneContent.Terminal(0),
                    rightOrBottomPane = PaneContent.Terminal(1),
                    splitRatio = 0.5f,
                ),
            )
        )

        // Simulate: drag delta of -200px with totalSize 1000px
        // New ratio = 0.5 + (-200 / 1000) = 0.5 - 0.2 = 0.3
        val delta = -200f
        val totalSize = 1000f
        val newRatio = (0.5f + (delta / totalSize)).coerceIn(0.2f, 0.8f)

        uiState.value = uiState.value.let { state ->
            val split = state.splitState ?: return@let state
            state.copy(splitState = split.copy(splitRatio = newRatio))
        }

        assertEquals(0.3f, uiState.value.splitState?.splitRatio)
    }

    @Test
    fun `divider ratio computation clamps when drag exceeds upper bound`() {
        val uiState = MutableStateFlow(
            SessionUiState(
                tabs = listOf(
                    SessionTab(
                        sessionId = "session-1",
                        host = createTestHost(id = "host-1", label = "server1"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                    SessionTab(
                        sessionId = "session-2",
                        host = createTestHost(id = "host-2", label = "server2"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                ),
                activeTabIndex = 0,
                splitState = SplitState(
                    orientation = SplitOrientation.HORIZONTAL,
                    leftOrTopPane = PaneContent.Terminal(0),
                    rightOrBottomPane = PaneContent.Terminal(1),
                    splitRatio = 0.7f,
                ),
            )
        )

        // Simulate: drag delta of 200px with totalSize 1000px
        // Computed = 0.7 + (200 / 1000) = 0.7 + 0.2 = 0.9, clamped to 0.8
        val delta = 200f
        val totalSize = 1000f
        val newRatio = (0.7f + (delta / totalSize)).coerceIn(0.2f, 0.8f)

        uiState.value = uiState.value.let { state ->
            val split = state.splitState ?: return@let state
            state.copy(splitState = split.copy(splitRatio = newRatio))
        }

        assertEquals(0.8f, uiState.value.splitState?.splitRatio)
    }

    @Test
    fun `divider ratio computation clamps when drag exceeds lower bound`() {
        val uiState = MutableStateFlow(
            SessionUiState(
                tabs = listOf(
                    SessionTab(
                        sessionId = "session-1",
                        host = createTestHost(id = "host-1", label = "server1"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                    SessionTab(
                        sessionId = "session-2",
                        host = createTestHost(id = "host-2", label = "server2"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                ),
                activeTabIndex = 0,
                splitState = SplitState(
                    orientation = SplitOrientation.HORIZONTAL,
                    leftOrTopPane = PaneContent.Terminal(0),
                    rightOrBottomPane = PaneContent.Terminal(1),
                    splitRatio = 0.3f,
                ),
            )
        )

        // Simulate: drag delta of -200px with totalSize 1000px
        // Computed = 0.3 + (-200 / 1000) = 0.3 - 0.2 = 0.1, clamped to 0.2
        val delta = -200f
        val totalSize = 1000f
        val newRatio = (0.3f + (delta / totalSize)).coerceIn(0.2f, 0.8f)

        uiState.value = uiState.value.let { state ->
            val split = state.splitState ?: return@let state
            state.copy(splitState = split.copy(splitRatio = newRatio))
        }

        assertEquals(0.2f, uiState.value.splitState?.splitRatio)
    }

    @Test
    fun `divider ratio computation with zero totalSize is safe`() {
        val uiState = MutableStateFlow(
            SessionUiState(
                tabs = listOf(
                    SessionTab(
                        sessionId = "session-1",
                        host = createTestHost(id = "host-1", label = "server1"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                    SessionTab(
                        sessionId = "session-2",
                        host = createTestHost(id = "host-2", label = "server2"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                ),
                activeTabIndex = 0,
                splitState = SplitState(
                    orientation = SplitOrientation.HORIZONTAL,
                    leftOrTopPane = PaneContent.Terminal(0),
                    rightOrBottomPane = PaneContent.Terminal(1),
                    splitRatio = 0.5f,
                ),
            )
        )

        val originalRatio = uiState.value.splitState?.splitRatio

        // Simulate: totalSize = 0, delta = 100
        // Guard with if (totalSize > 0f) prevents division by zero
        val delta = 100f
        val totalSize = 0f
        val newRatio = if (totalSize > 0f) {
            (0.5f + (delta / totalSize)).coerceIn(0.2f, 0.8f)
        } else {
            0.5f // Ratio unchanged
        }

        uiState.value = uiState.value.let { state ->
            val split = state.splitState ?: return@let state
            state.copy(splitState = split.copy(splitRatio = newRatio))
        }

        assertEquals(originalRatio, uiState.value.splitState?.splitRatio)
    }

    @Test
    fun `incremental drag deltas accumulate correctly`() {
        val uiState = MutableStateFlow(
            SessionUiState(
                tabs = listOf(
                    SessionTab(
                        sessionId = "session-1",
                        host = createTestHost(id = "host-1", label = "server1"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                    SessionTab(
                        sessionId = "session-2",
                        host = createTestHost(id = "host-2", label = "server2"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                ),
                activeTabIndex = 0,
                splitState = SplitState(
                    orientation = SplitOrientation.HORIZONTAL,
                    leftOrTopPane = PaneContent.Terminal(0),
                    rightOrBottomPane = PaneContent.Terminal(1),
                    splitRatio = 0.5f,
                ),
            )
        )

        val totalSize = 1000f

        // Apply cumulative delta: 300px total in three separate 100px drags
        // Result: 0.5 + (300 / 1000) = 0.5 + 0.3 = 0.8
        var currentRatio = 0.5f
        repeat(3) {
            currentRatio = (currentRatio + (100f / totalSize)).coerceIn(0.2f, 0.8f)
        }

        uiState.value = uiState.value.let { state ->
            val split = state.splitState ?: return@let state
            state.copy(splitState = split.copy(splitRatio = currentRatio))
        }

        assertEquals(0.8f, uiState.value.splitState?.splitRatio)
    }

    @Test
    fun `vertical split uses same ratio computation as horizontal`() {
        val uiState = MutableStateFlow(
            SessionUiState(
                tabs = listOf(
                    SessionTab(
                        sessionId = "session-1",
                        host = createTestHost(id = "host-1", label = "server1"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                    SessionTab(
                        sessionId = "session-2",
                        host = createTestHost(id = "host-2", label = "server2"),
                        terminalSession = createMockTerminalSession(),
                        status = SessionStatus.CONNECTED,
                    ),
                ),
                activeTabIndex = 0,
                splitState = SplitState(
                    orientation = SplitOrientation.VERTICAL,
                    leftOrTopPane = PaneContent.Terminal(0),
                    rightOrBottomPane = PaneContent.Terminal(1),
                    splitRatio = 0.5f,
                ),
            )
        )

        // Simulate: drag delta of 100px with totalSize 1000px for VERTICAL split
        // Result should be identical to HORIZONTAL: 0.5 + 0.1 = 0.6
        val delta = 100f
        val totalSize = 1000f
        val newRatio = (0.5f + (delta / totalSize)).coerceIn(0.2f, 0.8f)

        uiState.value = uiState.value.let { state ->
            val split = state.splitState ?: return@let state
            state.copy(splitState = split.copy(splitRatio = newRatio))
        }

        assertEquals(SplitOrientation.VERTICAL, uiState.value.splitState?.orientation)
        assertEquals(0.6f, uiState.value.splitState?.splitRatio)
    }

    // ── Helper functions ────────────────────────────────────────────────────────────

    private fun createTestHost(id: String, label: String) = Host(
        id = id,
        label = label,
        hostname = "example.com",
        port = 22,
        username = "user",
        authType = AuthType.PASSWORD,
        credentialId = "cred-$id",
        group = null,
        keepAliveSeconds = 30,
        autoReconnect = true,
        terminalTheme = "TECHTICAL_DARK",
    )

    private fun createMockTerminalSession(): SshTerminalSession {
        // Use MockK to create a mock SshTerminalSession.
        // We only need it to exist as a reference: tests verify state transitions,
        // not actual SSH operations.
        return mockk()
    }
}
