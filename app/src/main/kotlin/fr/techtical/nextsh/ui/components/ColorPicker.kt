// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fr.techtical.nextsh.R
import fr.techtical.nextsh.ui.theme.Border1
import fr.techtical.nextsh.ui.theme.Burgundy
import fr.techtical.nextsh.ui.theme.Gold
import fr.techtical.nextsh.ui.theme.JetBrainsMonoFamily
import fr.techtical.nextsh.ui.theme.NearBlack
import fr.techtical.nextsh.ui.theme.Radii
import fr.techtical.nextsh.ui.theme.SpaceGroteskFamily
import fr.techtical.nextsh.ui.theme.Spacing
import fr.techtical.nextsh.ui.theme.Surface
import fr.techtical.nextsh.ui.theme.SurfaceVariant
import fr.techtical.nextsh.ui.theme.TextPrimary
import fr.techtical.nextsh.ui.theme.TextSecondary
import fr.techtical.nextsh.ui.theme.White
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Reusable HSV color picker shown as a Compose [Dialog] over the theme editor.
 * Android port of the Desktop `ColorPickerWindow`, same UX, same conversion
 * helpers, but rendered as a modal dialog (Android has no `DialogWindow`).
 *
 * Single source of truth: an opaque ARGB [Int] (alpha forced to 0xFF). The wheel,
 * the RGB sliders, the brightness/lightness slider and the hex field all read and
 * write that one value, so editing any representation keeps the others in sync.
 *
 * Tab 1 "Roue" : HSV color wheel (hue = angle, saturation = radius) + a vertical
 * value/brightness slider. Tab 2 "RGB" : R/G/B sliders + a white→black brightness
 * slider. A live preview swatch and an editable hex field are shared by both tabs.
 *
 * @param initialArgb the slot's current ARGB color (alpha ignored, output is opaque).
 * @param onConfirm   receives the chosen opaque ARGB [Int] (alpha = 0xFF).
 * @param onDismiss   called on cancel / outside tap.
 */
@Composable
fun ColorPickerDialog(
    initialArgb: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // Single source of truth: the working HSV triple. RGB and hex are derived.
    // Keeping HSV (not RGB) as the canonical state avoids the wheel selector
    // "jumping" when saturation or value hit an edge during RGB round-trips.
    val initialHsv = remember(initialArgb) { argbToHsv(initialArgb) }
    var hue by remember(initialArgb) { mutableStateOf(initialHsv[0]) }
    var sat by remember(initialArgb) { mutableStateOf(initialHsv[1]) }
    var value by remember(initialArgb) { mutableStateOf(initialHsv[2]) }

    val currentArgb = hsvToArgb(hue, sat, value)

    // Hex text is a separate editable buffer so partial input ("#FF") doesn't
    // wipe the HSV state mid-typing. It re-syncs from HSV whenever the wheel /
    // sliders move (keyed on currentArgb below).
    var hexText by remember(initialArgb) { mutableStateOf(formatHexRgb(currentArgb)) }
    var lastPushedArgb by remember(initialArgb) { mutableStateOf(currentArgb) }
    if (currentArgb != lastPushedArgb) {
        hexText = formatHexRgb(currentArgb)
        lastPushedArgb = currentArgb
    }

    var tabIndex by remember { mutableStateOf(0) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(Radii.Lg))
                .background(NearBlack)
                .border(1.dp, Border1, RoundedCornerShape(Radii.Lg))
                .padding(Spacing.Lg),
        ) {
            Text(
                text = stringResource(R.string.color_picker_title),
                color = TextPrimary,
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
            Spacer(Modifier.height(Spacing.Md))

            TabRow(
                selectedTabIndex = tabIndex,
                containerColor = Surface,
                contentColor = Gold,
            ) {
                Tab(
                    selected = tabIndex == 0,
                    onClick = { tabIndex = 0 },
                    text = {
                        Text(
                            stringResource(R.string.color_picker_tab_wheel),
                            color = if (tabIndex == 0) Gold else TextSecondary,
                            fontSize = 13.sp,
                        )
                    },
                )
                Tab(
                    selected = tabIndex == 1,
                    onClick = { tabIndex = 1 },
                    text = {
                        Text(
                            stringResource(R.string.color_picker_tab_rgb),
                            color = if (tabIndex == 1) Gold else TextSecondary,
                            fontSize = 13.sp,
                        )
                    },
                )
            }

            Spacer(Modifier.height(Spacing.Md))

            when (tabIndex) {
                0 -> WheelTab(
                    hue = hue,
                    sat = sat,
                    value = value,
                    onHueSat = { h, s -> hue = h; sat = s },
                    onValue = { value = it },
                )
                else -> RgbTab(
                    argb = currentArgb,
                    value = value,
                    onRgb = { r, g, b ->
                        val hsv = rgbToHsv(r, g, b)
                        hue = hsv[0]; sat = hsv[1]; value = hsv[2]
                    },
                    onValue = { value = it },
                )
            }

            Spacer(Modifier.height(Spacing.Md))

            // Live preview swatch + editable hex (shared across both tabs).
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(Radii.Sm))
                        .background(Color(currentArgb))
                        .border(1.dp, Border1, RoundedCornerShape(Radii.Sm)),
                )
                Spacer(Modifier.width(Spacing.Sm))
                NextShTextField(
                    value = hexText,
                    onValueChange = { input ->
                        hexText = input
                        parseHexColor(input)?.let { parsed ->
                            val hsv = argbToHsv(parsed)
                            hue = hsv[0]; sat = hsv[1]; value = hsv[2]
                            lastPushedArgb = hsvToArgb(hsv[0], hsv[1], hsv[2])
                        }
                    },
                    label = stringResource(R.string.color_picker_hex),
                    isError = parseHexColor(hexText) == null,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(Spacing.Md))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel), color = TextSecondary)
                }
                Spacer(Modifier.width(Spacing.Sm))
                Button(
                    onClick = { onConfirm(opaque(currentArgb)) },
                    colors = ButtonDefaults.buttonColors(containerColor = Burgundy, contentColor = White),
                    shape = RoundedCornerShape(Radii.Md),
                ) {
                    Text(stringResource(R.string.action_save), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

// ── Tab 1: HSV wheel ──────────────────────────────────────────────────────────

@Composable
private fun WheelTab(
    hue: Float,
    sat: Float,
    value: Float,
    onHueSat: (Float, Float) -> Unit,
    onValue: (Float) -> Unit,
) {
    // Centre the wheel + brightness slider as a fixed-size group so the wheel
    // never bleeds to the dialog edges and the slider height matches the wheel.
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HsvWheel(
                hue = hue,
                sat = sat,
                value = value,
                onHueSat = onHueSat,
                modifier = Modifier.size(210.dp),
            )
            Spacer(Modifier.width(Spacing.Lg))
            // Vertical value/brightness slider (full color at top → black at bottom).
            VerticalValueSlider(
                hue = hue,
                sat = sat,
                value = value,
                onValue = onValue,
                modifier = Modifier
                    .width(28.dp)
                    .height(210.dp),
            )
        }
    }
}

/**
 * HSV color wheel: hue mapped to the angle around the centre, saturation to the
 * normalized radial distance (0 at centre → 1 at the rim). A sweep-gradient ring
 * provides the hue band; a radial white→transparent overlay desaturates toward
 * the centre. Value/brightness dims the whole disc. A draggable selector dot
 * marks the current (hue, sat).
 */
@Composable
private fun HsvWheel(
    hue: Float,
    sat: Float,
    value: Float,
    onHueSat: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier.pointerInput(Unit) {
            val handle: (Offset) -> Unit = { pos ->
                val r = size.width.coerceAtMost(size.height) / 2f
                val cx = size.width / 2f
                val cy = size.height / 2f
                val dx = pos.x - cx
                val dy = pos.y - cy
                val dist = sqrt(dx * dx + dy * dy)
                // atan2 returns radians in (-π, π]; map to [0, 360) for hue.
                var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                if (angle < 0f) angle += 360f
                val newSat = (dist / r).coerceIn(0f, 1f)
                onHueSat(angle, newSat)
            }
            detectTapGestures(onTap = handle)
        }.pointerInput(Unit) {
            detectDragGestures { change, _ ->
                val r = size.width.coerceAtMost(size.height) / 2f
                val cx = size.width / 2f
                val cy = size.height / 2f
                val dx = change.position.x - cx
                val dy = change.position.y - cy
                val dist = sqrt(dx * dx + dy * dy)
                var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                if (angle < 0f) angle += 360f
                onHueSat(angle, (dist / r).coerceIn(0f, 1f))
            }
        },
    ) {
        val r = min(size.width, size.height) / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        // Hue ring via sweep gradient (full saturation/value first; value dims after).
        // 0° starts at 3 o'clock (the +X axis), matching atan2's reference so the
        // selector dot angle lines up with the painted hue.
        val hueStops = (0..360 step 60).map { deg ->
            (deg / 360f) to Color(hsvToArgb(deg.toFloat() % 360f, 1f, 1f))
        }.toTypedArray()
        drawCircle(
            brush = Brush.sweepGradient(*hueStops, center = center),
            radius = r,
            center = center,
        )
        // Radial white→transparent overlay desaturates toward the centre.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White, Color.White.copy(alpha = 0f)),
                center = center,
                radius = r,
            ),
            radius = r,
            center = center,
        )
        // The wheel always shows hue/saturation at full brightness so it stays a
        // usable color picker even when the selected value is dark (e.g. editing
        // a black ANSI color). Brightness is set by the separate vertical slider
        // and reflected in the selector dot + preview swatch, NOT by dimming the
        // whole disc (which previously blacked the wheel out at value 0).

        // Selector dot at (hue angle, sat radius).
        val angleRad = Math.toRadians(hue.toDouble())
        val selDist = sat * r
        val selX = center.x + (cos(angleRad) * selDist).toFloat()
        val selY = center.y + (sin(angleRad) * selDist).toFloat()
        val ringColor = if (value > 0.5f) Color.Black else Color.White
        drawCircle(color = ringColor, radius = 7f, center = Offset(selX, selY))
        drawCircle(color = Color(hsvToArgb(hue, sat, value)), radius = 5f, center = Offset(selX, selY))
    }
}

/**
 * Vertical brightness/value slider rendered as a gradient from the fully-bright
 * (hue, sat) colour at the top to black at the bottom. Dragging anywhere along
 * the track sets value = 1 - (y / height).
 */
@Composable
private fun VerticalValueSlider(
    hue: Float,
    sat: Float,
    value: Float,
    onValue: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(Radii.Md))
            .border(1.dp, Border1, RoundedCornerShape(Radii.Md))
            .pointerInput(Unit) {
                detectTapGestures { pos -> onValue((1f - pos.y / size.height).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    onValue((1f - change.position.y / size.height).coerceIn(0f, 1f))
                }
            },
    ) {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color(hsvToArgb(hue, sat, 1f)), Color.Black),
            ),
        )
        // Thumb indicator at the current value.
        val thumbY = (1f - value) * size.height
        drawRect(
            color = Color.White,
            topLeft = Offset(0f, (thumbY - 2f).coerceIn(0f, size.height - 4f)),
            size = Size(size.width, 4f),
        )
    }
}

// ── Tab 2: RGB sliders + brightness ─────────────────────────────────────────

@Composable
private fun RgbTab(
    argb: Int,
    value: Float,
    onRgb: (Int, Int, Int) -> Unit,
    onValue: (Float) -> Unit,
) {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ChannelSlider(
            label = stringResource(R.string.color_picker_red),
            channelValue = r,
            track = Brush.horizontalGradient(listOf(Color(0xFF000000), Color(0xFFFF0000))),
            onChange = { onRgb(it, g, b) },
        )
        ChannelSlider(
            label = stringResource(R.string.color_picker_green),
            channelValue = g,
            track = Brush.horizontalGradient(listOf(Color(0xFF000000), Color(0xFF00FF00))),
            onChange = { onRgb(r, it, b) },
        )
        ChannelSlider(
            label = stringResource(R.string.color_picker_blue),
            channelValue = b,
            track = Brush.horizontalGradient(listOf(Color(0xFF000000), Color(0xFF0000FF))),
            onChange = { onRgb(r, g, it) },
        )
        Spacer(Modifier.height(2.dp))
        // White→black brightness/lightness slider.
        Text(
            stringResource(R.string.color_picker_lightness),
            color = TextSecondary,
            fontSize = 11.sp,
        )
        HorizontalGradientSlider(
            fraction = value,
            track = Brush.horizontalGradient(listOf(Color.White, Color.Black)),
            onChange = { onValue((1f - it).coerceIn(0f, 1f)) },
        )
    }
}

/**
 * One RGB channel: a label + numeric readout above a gradient track. Tapping or
 * dragging the track sets the 0..255 channel value from the x position.
 */
@Composable
private fun ChannelSlider(
    label: String,
    channelValue: Int,
    track: Brush,
    onChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = TextSecondary, fontSize = 11.sp)
            Text(
                channelValue.toString(),
                color = TextPrimary,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 11.sp,
            )
        }
        HorizontalGradientSlider(
            fraction = channelValue / 255f,
            track = track,
            onChange = { onChange((it * 255f).roundToInt().coerceIn(0, 255)) },
        )
    }
}

/**
 * Horizontal gradient track with a thumb at [fraction] (0..1). Reports the
 * tapped/dragged fraction via [onChange].
 */
@Composable
private fun HorizontalGradientSlider(
    fraction: Float,
    track: Brush,
    onChange: (Float) -> Unit,
) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(RoundedCornerShape(Radii.Sm))
            .border(1.dp, Border1, RoundedCornerShape(Radii.Sm))
            .background(SurfaceVariant)
            .pointerInput(Unit) {
                detectTapGestures { pos -> onChange((pos.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    onChange((change.position.x / size.width).coerceIn(0f, 1f))
                }
            },
    ) {
        drawRect(brush = track)
        val thumbX = fraction.coerceIn(0f, 1f) * size.width
        drawRect(
            color = Color.White,
            topLeft = Offset((thumbX - 2f).coerceIn(0f, size.width - 4f), 0f),
            size = Size(4f, size.height),
        )
    }
}

// ── HSV ↔ RGB conversion (no dependency) ─────────────────────────────────────

/** Forces alpha to 0xFF: terminal colors are always opaque. */
internal fun opaque(argb: Int): Int = (0xFF000000.toInt()) or (argb and 0x00FFFFFF)

/**
 * HSV → opaque ARGB. [h] in [0,360), [s] and [v] in [0,1]. Standard sextant
 * algorithm; result always has alpha 0xFF.
 */
internal fun hsvToArgb(h: Float, s: Float, v: Float): Int {
    val hue = ((h % 360f) + 360f) % 360f
    val sat = s.coerceIn(0f, 1f)
    val value = v.coerceIn(0f, 1f)
    val c = value * sat
    val x = c * (1f - kotlin.math.abs((hue / 60f) % 2f - 1f))
    val m = value - c
    val (r1, g1, b1) = when {
        hue < 60f -> Triple(c, x, 0f)
        hue < 120f -> Triple(x, c, 0f)
        hue < 180f -> Triple(0f, c, x)
        hue < 240f -> Triple(0f, x, c)
        hue < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val r = ((r1 + m) * 255f).roundToInt().coerceIn(0, 255)
    val g = ((g1 + m) * 255f).roundToInt().coerceIn(0, 255)
    val b = ((b1 + m) * 255f).roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

/** ARGB → HSV float[3] = {hue [0,360), sat [0,1], value [0,1]}. */
internal fun argbToHsv(argb: Int): FloatArray =
    rgbToHsv((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

/** RGB (0..255 each) → HSV float[3]. */
internal fun rgbToHsv(r: Int, g: Int, b: Int): FloatArray {
    val rf = r / 255f
    val gf = g / 255f
    val bf = b / 255f
    val max = maxOf(rf, gf, bf)
    val min = minOf(rf, gf, bf)
    val delta = max - min
    var h = when {
        delta == 0f -> 0f
        max == rf -> 60f * (((gf - bf) / delta) % 6f)
        max == gf -> 60f * (((bf - rf) / delta) + 2f)
        else -> 60f * (((rf - gf) / delta) + 4f)
    }
    if (h < 0f) h += 360f
    val s = if (max == 0f) 0f else delta / max
    return floatArrayOf(h, s, max)
}

/** Formats an ARGB int as #RRGGBB (opaque, alpha dropped) for the picker hex field. */
internal fun formatHexRgb(argb: Int): String {
    val rgb = argb and 0x00FFFFFF
    return "#" + rgb.toString(16).uppercase().padStart(6, '0')
}

/** Parses a #RRGGBB or #AARRGGBB hex string into an ARGB int, or null if invalid. */
internal fun parseHexColor(input: String): Int? {
    val s = input.trim().removePrefix("#")
    return when (s.length) {
        6 -> s.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
        8 -> s.toLongOrNull(16)?.toInt()
        else -> null
    }
}
