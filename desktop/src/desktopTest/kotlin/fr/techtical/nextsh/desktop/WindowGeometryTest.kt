// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import fr.techtical.nextsh.desktop.data.preferences.DesktopSettingsStore.WindowGeometry
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit tests for the window geometry rules of `Main.kt`:
 *
 *  - [resolveInitialWindowState]: what the OS is told to restore to when the
 *    app starts (always maximized), including the repair of prefs written by
 *    earlier builds, which persisted the MAXIMIZED bounds as if they were the
 *    floating geometry;
 *  - [nextFloatingGeometry]: the pure rule that decides when an observed
 *    window state may overwrite the remembered floating geometry;
 *  - [openingGeometry]: the bounds the window is created with (the usable
 *    area of the launch screen on Windows, so it opens without a visible
 *    resize; the floating geometry elsewhere);
 *  - [chooseLaunchScreen]: which screen the app reopens on (the one it was
 *    left on, else the primary one);
 *  - [relocateFloatingToScreen] / [liveWindowRect]: keeping the persisted
 *    geometry on the screen the window really is on, even when a MAXIMIZED
 *    window was sent to another screen.
 *
 * All are pure (no Compose runtime, no AWT window), so they run headless.
 * The multi-screen cases use the reporter's real layout: the laptop below plus
 * a 27" placed above it and shifted left (negative coordinates).
 *
 * Rounding convention for the default size: 80 % of the usable area is
 * computed in integer arithmetic (`dimension * 80 / 100`), i.e. truncated, so
 * the result never exceeds 80 %. A 1536×816 usable area gives 1228×652
 * (1228.8 and 652.8 truncated).
 */
class WindowGeometryTest {

    /** Usable area of the reporter's laptop: 1920×1080 at 125 % minus the taskbar. */
    private val laptop = Rectangle(0, 0, 1536, 816)

    /** Roomy desktop screen: the 1280×800 default fits well under 80 %. */
    private val bigScreen = Rectangle(0, 0, 2560, 1392)

    /**
     * Second screen of the reporter: 27" 2560×1440 at 100 % above the laptop,
     * shifted left, minus its own taskbar (usable height 1392).
     */
    private val monitor = Rectangle(-338, -1440, 2560, 1392)

    private val bothScreens = listOf(laptop, monitor)

    /** Maximized bounds of the laptop: usable area plus the 7 px invisible frame. */
    private val laptopMaximized = Rectangle(-7, -7, 1550, 830)

    /** Same on the 27": usable area plus the 7 px invisible frame on each side. */
    private val monitorMaximized = Rectangle(-345, -1447, 2574, 1406)

    private fun assertState(
        state: InitialWindowState,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        assertTrue(state.maximized, "the app must always launch maximized")
        assertEquals(WindowPosition(x.dp, y.dp), state.floatingPosition, "floating position")
        assertEquals(DpSize(width.dp, height.dp), state.floatingSize, "floating size")
    }

    private fun geometry(x: Int, y: Int, width: Int, height: Int) =
        FloatingGeometry(WindowPosition(x.dp, y.dp), DpSize(width.dp, height.dp))

    // ── resolveInitialWindowState: nothing usable saved ───────────────────────

    @Test
    fun `unset prefs give the default size capped at 80 percent and centered`() {
        val state = resolveInitialWindowState(WindowGeometry(), laptop)

        // (1536 - 1228) / 2 = 154 ; (816 - 652) / 2 = 82
        assertState(state, x = 154, y = 82, width = 1228, height = 652)
    }

    @Test
    fun `default size respects the usable area origin when the taskbar is on top`() {
        val state = resolveInitialWindowState(WindowGeometry(), Rectangle(0, 40, 1920, 1040))

        // 80 % of 1920 = 1536 > 1280 ; 80 % of 1040 = 832 > 800 -> 1280×800
        // x = (1920 - 1280) / 2 ; y = 40 + (1040 - 800) / 2
        assertState(state, x = 320, y = 160, width = 1280, height = 800)
    }

    @Test
    fun `corrupt tiny prefs fall back to the default size`() {
        val state = resolveInitialWindowState(
            WindowGeometry(x = 100, y = 100, width = 5, height = 5, maximized = false),
            laptop,
        )

        assertState(state, x = 154, y = 82, width = 1228, height = 652)
    }

    @Test
    fun `prefs below the minimum on a single dimension are corrupt`() {
        val state = resolveInitialWindowState(
            WindowGeometry(x = 100, y = 100, width = 1400, height = 500),
            bigScreen,
        )

        assertState(state, x = 640, y = 296, width = 1280, height = 800)
    }

    // ── resolveInitialWindowState: the reported bug ───────────────────────────

    @Test
    fun `maximized bounds persisted by earlier builds are repaired to the default size`() {
        // Exact prefs found on the reporter's machine: the maximized bounds
        // (usable area 1536×816 plus the 7 px invisible frame on each side).
        val state = resolveInitialWindowState(
            WindowGeometry(x = -7, y = -7, width = 1550, height = 830, maximized = true),
            laptop,
        )

        assertState(state, x = 154, y = 82, width = 1228, height = 652)
    }

    @Test
    fun `default size is never below the window minimum on the reported laptop`() {
        val state = resolveInitialWindowState(
            WindowGeometry(x = -7, y = -7, width = 1550, height = 830),
            laptop,
        )

        assertTrue(state.floatingSize.width.value >= MIN_FLOATING_WIDTH_DP)
        assertTrue(state.floatingSize.height.value >= MIN_FLOATING_HEIGHT_DP)
    }

    @Test
    fun `a saved size filling the width is treated as a maximized leftover`() {
        // 95 % of 2000 is exactly 1900.
        val usable = Rectangle(0, 0, 2000, 1200)

        val state = resolveInitialWindowState(WindowGeometry(x = 50, y = 50, width = 1900, height = 900), usable)

        // Default: min(1280, 1600) x min(800, 960), centered.
        assertState(state, x = 360, y = 200, width = 1280, height = 800)
    }

    @Test
    fun `a saved size filling the height is treated as a maximized leftover`() {
        // 95 % of 1200 is exactly 1140.
        val usable = Rectangle(0, 0, 2000, 1200)

        val state = resolveInitialWindowState(WindowGeometry(x = 50, y = 50, width = 1400, height = 1140), usable)

        assertState(state, x = 360, y = 200, width = 1280, height = 800)
    }

    @Test
    fun `a saved size just under 95 percent on both dimensions is kept`() {
        val usable = Rectangle(0, 0, 2000, 1200)

        // 1899 / 2000 = 94.95 % ; 1139 / 1200 = 94.92 %
        val state = resolveInitialWindowState(WindowGeometry(x = 50, y = 50, width = 1899, height = 1139), usable)

        assertState(state, x = 50, y = 50, width = 1899, height = 1139)
    }

    // ── resolveInitialWindowState: normal saved geometry ──────────────────────

    @Test
    fun `big screen keeps the 1280 by 800 default on first launch`() {
        val state = resolveInitialWindowState(WindowGeometry(), bigScreen)

        // (2560 - 1280) / 2 = 640 ; (1392 - 800) / 2 = 296
        assertState(state, x = 640, y = 296, width = 1280, height = 800)
    }

    @Test
    fun `a normal saved size and position are kept as is`() {
        val state = resolveInitialWindowState(
            WindowGeometry(x = 100, y = 100, width = 1400, height = 900, maximized = false),
            bigScreen,
        )

        assertState(state, x = 100, y = 100, width = 1400, height = 900)
    }

    @Test
    fun `a saved geometry always launches maximized whatever the saved flag`() {
        val withFlag = resolveInitialWindowState(
            WindowGeometry(x = 100, y = 100, width = 1400, height = 900, maximized = true),
            bigScreen,
        )
        val withoutFlag = resolveInitialWindowState(
            WindowGeometry(x = 100, y = 100, width = 1400, height = 900, maximized = false),
            bigScreen,
        )

        assertTrue(withFlag.maximized)
        assertTrue(withoutFlag.maximized)
    }

    @Test
    fun `a normal saved size on a vanished display keeps its size and is recentered`() {
        val state = resolveInitialWindowState(
            WindowGeometry(x = 5000, y = 5000, width = 1400, height = 900),
            bigScreen,
        )

        // Centered for the SAVED size: (2560 - 1400) / 2 = 580 ; (1392 - 900) / 2 = 246
        assertState(state, x = 580, y = 246, width = 1400, height = 900)
    }

    @Test
    fun `a saved window partly on screen keeps its position`() {
        val state = resolveInitialWindowState(
            WindowGeometry(x = 2000, y = 100, width = 1400, height = 900),
            bigScreen,
        )

        assertState(state, x = 2000, y = 100, width = 1400, height = 900)
    }

    // ── resolveInitialWindowState: small screens ──────────────────────────────

    @Test
    fun `small screen where 80 percent is under the minimum applies the minimum`() {
        // 80 % of 1100×650 is 880×520, below 1024×600.
        val state = resolveInitialWindowState(WindowGeometry(), Rectangle(0, 0, 1100, 650))

        // (1100 - 1024) / 2 = 38 ; (650 - 600) / 2 = 25
        assertState(state, x = 38, y = 25, width = 1024, height = 600)
    }

    @Test
    fun `usable area smaller than the minimum caps the default at the usable area`() {
        val state = resolveInitialWindowState(WindowGeometry(), Rectangle(0, 0, 900, 500))

        assertState(state, x = 0, y = 0, width = 900, height = 500)
    }

    @Test
    fun `minimum is applied per dimension on a short wide screen`() {
        // Real-world 1366×768 laptop minus a 40 px taskbar: 1366×728.
        // 80 % -> 1092×582, so only the height is raised to 600.
        val state = resolveInitialWindowState(WindowGeometry(), Rectangle(0, 0, 1366, 728))

        // (1366 - 1092) / 2 = 137 ; (728 - 600) / 2 = 64
        assertState(state, x = 137, y = 64, width = 1092, height = 600)
    }

    // ── nextFloatingGeometry ──────────────────────────────────────────────────

    @Test
    fun `floating placement updates the remembered geometry`() {
        val current = geometry(100, 100, 1400, 900)

        val next = nextFloatingGeometry(
            current = current,
            placement = WindowPlacement.Floating,
            position = WindowPosition(154.dp, 82.dp),
            size = DpSize(1228.dp, 652.dp),
        )

        assertEquals(geometry(154, 82, 1228, 652), next)
    }

    @Test
    fun `maximized placement leaves the remembered geometry untouched`() {
        val current = geometry(100, 100, 1400, 900)

        val next = nextFloatingGeometry(
            current = current,
            placement = WindowPlacement.Maximized,
            position = WindowPosition((-7).dp, (-7).dp),
            size = DpSize(1550.dp, 830.dp),
        )

        assertSame(current, next)
    }

    @Test
    fun `fullscreen placement leaves the remembered geometry untouched`() {
        val current = geometry(100, 100, 1400, 900)

        val next = nextFloatingGeometry(
            current = current,
            placement = WindowPlacement.Fullscreen,
            position = WindowPosition(0.dp, 0.dp),
            size = DpSize(1920.dp, 1080.dp),
        )

        assertSame(current, next)
    }

    @Test
    fun `platform default position is ignored`() {
        val current = geometry(100, 100, 1400, 900)

        val next = nextFloatingGeometry(
            current = current,
            placement = WindowPlacement.Floating,
            position = WindowPosition.PlatformDefault,
            size = DpSize(1228.dp, 652.dp),
        )

        assertSame(current, next)
    }

    @Test
    fun `aligned position is ignored`() {
        val current = geometry(100, 100, 1400, 900)

        val next = nextFloatingGeometry(
            current = current,
            placement = WindowPlacement.Floating,
            position = WindowPosition(Alignment.Center),
            size = DpSize(1228.dp, 652.dp),
        )

        assertSame(current, next)
    }

    @Test
    fun `unspecified size is ignored`() {
        val current = geometry(100, 100, 1400, 900)

        val next = nextFloatingGeometry(
            current = current,
            placement = WindowPlacement.Floating,
            position = WindowPosition(154.dp, 82.dp),
            size = DpSize.Unspecified,
        )

        assertSame(current, next)
    }

    @Test
    fun `size below the window minimum is ignored`() {
        val current = geometry(100, 100, 1400, 900)

        // The size Windows reports for a minimized window.
        val tiny = nextFloatingGeometry(
            current = current,
            placement = WindowPlacement.Floating,
            position = WindowPosition((-32000).dp, (-32000).dp),
            size = DpSize(160.dp, 28.dp),
        )
        val narrow = nextFloatingGeometry(
            current = current,
            placement = WindowPlacement.Floating,
            position = WindowPosition(154.dp, 82.dp),
            size = DpSize((MIN_FLOATING_WIDTH_DP - 1).dp, 700.dp),
        )
        val short = nextFloatingGeometry(
            current = current,
            placement = WindowPlacement.Floating,
            position = WindowPosition(154.dp, 82.dp),
            size = DpSize(1100.dp, (MIN_FLOATING_HEIGHT_DP - 1).dp),
        )

        assertSame(current, tiny)
        assertSame(current, narrow)
        assertSame(current, short)
    }

    @Test
    fun `exactly the minimum size is accepted`() {
        val next = nextFloatingGeometry(
            current = geometry(100, 100, 1400, 900),
            placement = WindowPlacement.Floating,
            position = WindowPosition(10.dp, 20.dp),
            size = DpSize(MIN_FLOATING_WIDTH_DP.dp, MIN_FLOATING_HEIGHT_DP.dp),
        )

        assertEquals(geometry(10, 20, MIN_FLOATING_WIDTH_DP, MIN_FLOATING_HEIGHT_DP), next)
    }

    @Test
    fun `maximize then restore remembers the restored geometry not the maximized bounds`() {
        // Replays the sequence of samples observed during the reported bug.
        var remembered = geometry(154, 82, 1228, 652)

        // 1. The user drags and resizes the floating window.
        remembered = nextFloatingGeometry(
            remembered,
            WindowPlacement.Floating,
            WindowPosition(100.dp, 100.dp),
            DpSize(1400.dp, 900.dp),
        )
        assertEquals(geometry(100, 100, 1400, 900), remembered)

        // 2. Maximize: Compose now reports the maximized bounds.
        remembered = nextFloatingGeometry(
            remembered,
            WindowPlacement.Maximized,
            WindowPosition((-7).dp, (-7).dp),
            DpSize(1550.dp, 830.dp),
        )
        assertEquals(geometry(100, 100, 1400, 900), remembered)

        // 3. Restore: the floating bounds are reported again.
        remembered = nextFloatingGeometry(
            remembered,
            WindowPlacement.Floating,
            WindowPosition(154.dp, 82.dp),
            DpSize(1228.dp, 652.dp),
        )
        assertEquals(geometry(154, 82, 1228, 652), remembered)
    }

    // ── chooseLaunchScreen ────────────────────────────────────────────────────

    @Test
    fun `prefs saved on the 27 inch screen reopen on it`() {
        val saved = WindowGeometry(x = -300, y = -1400, width = 1228, height = 652)

        assertSame(monitor, chooseLaunchScreen(saved, bothScreens, laptop))
    }

    @Test
    fun `prefs saved on the laptop reopen on it`() {
        val saved = WindowGeometry(x = 154, y = 82, width = 1228, height = 652)

        assertSame(laptop, chooseLaunchScreen(saved, bothScreens, laptop))
    }

    @Test
    fun `unset prefs open on the primary screen`() {
        // The primary screen is not first in the list, to prove it is the
        // `primary` argument that counts and not the list order.
        assertSame(laptop, chooseLaunchScreen(WindowGeometry(), listOf(monitor, laptop), laptop))
    }

    @Test
    fun `prefs saved on an unplugged screen fall back to the primary screen`() {
        val saved = WindowGeometry(x = -300, y = -1400, width = 1228, height = 652)

        assertSame(laptop, chooseLaunchScreen(saved, listOf(laptop), laptop))
    }

    @Test
    fun `a failed screen enumeration falls back to the primary screen`() {
        val saved = WindowGeometry(x = -300, y = -1400, width = 1228, height = 652)

        assertSame(laptop, chooseLaunchScreen(saved, emptyList(), laptop))
    }

    @Test
    fun `maximized bounds left by earlier builds on the laptop still pick the laptop`() {
        val saved = WindowGeometry(x = -7, y = -7, width = 1550, height = 830, maximized = true)

        assertSame(laptop, chooseLaunchScreen(saved, bothScreens, laptop))
    }

    @Test
    fun `an unplugged screen recenters the saved size on the primary screen`() {
        val saved = WindowGeometry(x = -300, y = -1400, width = 1228, height = 652)

        val launch = chooseLaunchScreen(saved, listOf(laptop), laptop)
        val state = resolveInitialWindowState(saved, launch)

        // Size kept (it does not fill the laptop), position recentered there.
        assertState(state, x = 154, y = 82, width = 1228, height = 652)
    }

    // ── resolveInitialWindowState: second screen as launch screen ─────────────

    @Test
    fun `unset prefs on the 27 inch screen give the 1280 by 800 default centered on it`() {
        val state = resolveInitialWindowState(WindowGeometry(), monitor)

        // x = -338 + (2560 - 1280) / 2 ; y = -1440 + (1392 - 800) / 2
        assertState(state, x = 302, y = -1144, width = 1280, height = 800)
    }

    @Test
    fun `a 1550 by 830 size is kept on the 27 inch screen with its position`() {
        // The very size that "fills" the laptop (and is repaired there) is
        // an ordinary floating size on the 27".
        val saved = WindowGeometry(x = -300, y = -1400, width = 1550, height = 830)

        assertState(resolveInitialWindowState(saved, monitor), x = -300, y = -1400, width = 1550, height = 830)
        assertState(resolveInitialWindowState(saved, laptop), x = 154, y = 82, width = 1228, height = 652)
    }

    @Test
    fun `maximized bounds of the 27 inch screen are repaired to its default size`() {
        val saved = WindowGeometry(
            x = monitorMaximized.x,
            y = monitorMaximized.y,
            width = monitorMaximized.width,
            height = monitorMaximized.height,
            maximized = true,
        )

        assertState(resolveInitialWindowState(saved, monitor), x = 302, y = -1144, width = 1280, height = 800)
    }

    // ── fillsScreen ───────────────────────────────────────────────────────────

    @Test
    fun `fillsScreen is true from 95 percent of either dimension`() {
        val usable = Rectangle(0, 0, 2000, 1200)

        assertTrue(fillsScreen(1900, 100, usable), "width exactly 95 %")
        assertTrue(fillsScreen(100, 1140, usable), "height exactly 95 %")
        assertTrue(fillsScreen(2400, 1500, usable), "bigger than the screen")
        assertFalse(fillsScreen(1899, 1139, usable), "just under 95 % on both")
    }

    // ── liveWindowRect ────────────────────────────────────────────────────────

    @Test
    fun `liveWindowRect reads the absolute position and size`() {
        val rect = liveWindowRect(false, WindowPosition((-7).dp, (-7).dp), DpSize(1550.dp, 830.dp))

        assertEquals(laptopMaximized, rect)
    }

    @Test
    fun `liveWindowRect ignores a minimized window`() {
        val rect = liveWindowRect(true, WindowPosition((-32000).dp, (-32000).dp), DpSize(160.dp, 28.dp))

        assertNull(rect)
    }

    @Test
    fun `liveWindowRect ignores a position that is not absolute or not specified`() {
        val size = DpSize(1228.dp, 652.dp)

        assertNull(liveWindowRect(false, WindowPosition.PlatformDefault, size))
        assertNull(liveWindowRect(false, WindowPosition(Alignment.Center), size))
        assertNull(liveWindowRect(false, WindowPosition(Dp.Unspecified, 82.dp), size))
        assertNull(liveWindowRect(false, WindowPosition(154.dp, Dp.Unspecified), size))
    }

    @Test
    fun `liveWindowRect ignores an unspecified size`() {
        assertNull(liveWindowRect(false, WindowPosition(154.dp, 82.dp), DpSize.Unspecified))
    }

    // ── relocateFloatingToScreen ──────────────────────────────────────────────

    /** Floating geometry of the laptop's default size, centered on it. */
    private val laptopFloating = Rectangle(154, 82, 1228, 652)

    @Test
    fun `a window on the same screen as the floating geometry leaves it unchanged`() {
        val result = relocateFloatingToScreen(laptopFloating, laptopMaximized, bothScreens)

        assertSame(laptopFloating, result)
    }

    @Test
    fun `a floating window on its own screen leaves the geometry unchanged`() {
        // Live bounds equal to the floating ones (no maximize involved).
        assertSame(laptopFloating, relocateFloatingToScreen(laptopFloating, laptopFloating, bothScreens))
    }

    @Test
    fun `a maximized window sent to the 27 inch screen drags the floating geometry along`() {
        val result = relocateFloatingToScreen(laptopFloating, monitorMaximized, bothScreens)

        // Same 1228×652 (it does not fill the 27"), centered on the 27":
        // x = -338 + (2560 - 1228) / 2 ; y = -1440 + (1392 - 652) / 2
        assertEquals(Rectangle(328, -1070, 1228, 652), result)
    }

    @Test
    fun `a floating size too big for the destination screen becomes its default size`() {
        // Floating on the 27" at 1800×1000, window maximized back on the laptop.
        val floating = Rectangle(-100, -1300, 1800, 1000)

        val result = relocateFloatingToScreen(floating, laptopMaximized, bothScreens)

        // 1800 fills the laptop width: default 1228×652, centered there.
        assertEquals(Rectangle(154, 82, 1228, 652), result)
    }

    @Test
    fun `a floating size that still fits the destination screen is kept`() {
        // 1400×700 is under 95 % of the laptop on both dimensions.
        val floating = Rectangle(-100, -1300, 1400, 700)

        val result = relocateFloatingToScreen(floating, laptopMaximized, bothScreens)

        // x = (1536 - 1400) / 2 ; y = (816 - 700) / 2
        assertEquals(Rectangle(68, 58, 1400, 700), result)
    }

    @Test
    fun `an unknown live window leaves the floating geometry unchanged`() {
        assertSame(laptopFloating, relocateFloatingToScreen(laptopFloating, null, bothScreens))
    }

    @Test
    fun `a minimized window leaves the floating geometry unchanged`() {
        val minimized = Rectangle(-32000, -32000, 160, 28)

        assertSame(laptopFloating, relocateFloatingToScreen(laptopFloating, minimized, bothScreens))
    }

    @Test
    fun `a window on no known screen leaves the floating geometry unchanged`() {
        val offScreen = Rectangle(5000, 5000, 1228, 652)

        assertSame(laptopFloating, relocateFloatingToScreen(laptopFloating, offScreen, bothScreens))
    }

    @Test
    fun `a failed screen enumeration leaves the floating geometry unchanged`() {
        assertSame(laptopFloating, relocateFloatingToScreen(laptopFloating, monitorMaximized, emptyList()))
    }

    @Test
    fun `a floating geometry left on a vanished screen is brought onto the window screen`() {
        val vanished = Rectangle(5000, 5000, 1228, 652)

        val result = relocateFloatingToScreen(vanished, laptopMaximized, bothScreens)

        assertEquals(Rectangle(154, 82, 1228, 652), result)
    }

    @Test
    fun `relocating twice gives the same geometry`() {
        // Persistence runs on every placement change: the second pass sees the
        // geometry already on the window's screen and must not move it again.
        val once = relocateFloatingToScreen(laptopFloating, monitorMaximized, bothScreens)
        val twice = relocateFloatingToScreen(once, monitorMaximized, bothScreens)

        assertSame(once, twice)
    }

    @Test
    fun `the relocated geometry reopens on the 27 inch screen`() {
        // End to end: maximize on the laptop, send the window to the 27", quit,
        // relaunch. The prefs written by the quit must select the 27".
        val persisted = relocateFloatingToScreen(laptopFloating, monitorMaximized, bothScreens)
        val saved = WindowGeometry(persisted.x, persisted.y, persisted.width, persisted.height, maximized = true)

        val launch = chooseLaunchScreen(saved, bothScreens, laptop)
        val state = resolveInitialWindowState(saved, launch)

        assertSame(monitor, launch)
        assertState(state, x = 328, y = -1070, width = 1228, height = 652)
    }

    // ── openingGeometry ───────────────────────────────────────────────────────

    @Test
    fun `on Windows the window is created at the usable area of the launch screen`() {
        val initial = resolveInitialWindowState(WindowGeometry(), laptop)

        val opening = openingGeometry(initial, laptop, openAtUsableArea = true)

        // Not the 1228×652 floating geometry: creating the window at its final
        // size is what avoids a visible resize when Windows maximizes it.
        assertEquals(geometry(x = 0, y = 0, width = 1536, height = 816), opening)
    }

    @Test
    fun `on Windows the creation bounds follow the launch screen origin`() {
        val saved = WindowGeometry(-138, -1340, 1228, 652, maximized = true)
        val launch = chooseLaunchScreen(saved, bothScreens, laptop)
        val initial = resolveInitialWindowState(saved, launch)

        val opening = openingGeometry(initial, launch, openAtUsableArea = true)

        assertSame(monitor, launch)
        assertEquals(geometry(x = -338, y = -1440, width = 2560, height = 1392), opening)
    }

    @Test
    fun `on Windows the creation bounds keep a taskbar on top out of the window`() {
        val usable = Rectangle(0, 40, 1920, 1040)
        val initial = resolveInitialWindowState(WindowGeometry(), usable)

        assertEquals(
            geometry(x = 0, y = 40, width = 1920, height = 1040),
            openingGeometry(initial, usable, openAtUsableArea = true),
        )
    }

    @Test
    fun `on other platforms the window is created at the floating geometry`() {
        val initial = resolveInitialWindowState(WindowGeometry(), laptop)

        val opening = openingGeometry(initial, laptop, openAtUsableArea = false)

        assertEquals(geometry(x = 154, y = 82, width = 1228, height = 652), opening)
    }

    @Test
    fun `on other platforms a saved floating geometry is passed through untouched`() {
        val saved = WindowGeometry(300, 120, 1100, 700, maximized = true)
        val initial = resolveInitialWindowState(saved, laptop)

        val opening = openingGeometry(initial, laptop, openAtUsableArea = false)

        assertEquals(geometry(x = 300, y = 120, width = 1100, height = 700), opening)
    }
}
