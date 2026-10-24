package com.classicplayer

import android.content.Context
import android.util.TypedValue
import android.view.View

/** Paleta no padrão iOS. */
object Ui {
    val BG = 0xFFF2F2F7.toInt()
    val SEPARATOR = 0xFFC6C6C8.toInt()
    val BLUE = 0xFF007AFF.toInt()
    val LABEL = 0xFF000000.toInt()
    val SECONDARY = 0xFF8E8E93.toInt()
    val CHEVRON = 0xFFC7C7CC.toInt()
    val GREEN = 0xFF34C759.toInt()
    val RED = 0xFFFF3B30.toInt()
}

fun Context.dpf(v: Number): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics)

fun Context.dpi(v: Number): Int = dpf(v).toInt()

fun Context.spf(v: Number): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v.toFloat(), resources.displayMetrics)

fun View.dpf(v: Number): Float = context.dpf(v)
fun View.dpi(v: Number): Int = context.dpi(v)
fun View.spf(v: Number): Float = context.spf(v)

fun formatTime(ms: Int): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    return "$m:${if (s < 10) "0" else ""}$s"
}
