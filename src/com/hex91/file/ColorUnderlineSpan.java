package com.hex91.file;

/*#######################################################
 *
 *   Maintained 2018-2025 by Gregor Santner <gsantner AT mailbox DOT org>
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 *   移植自 Markor（gsantner/markor）format/general/ColorUnderlineSpan，
 *   去除了 GsContextUtils.parseHexColorString 依赖（改由调用方解析颜色）。
 *
#########################################################*/

import android.text.TextPaint;
import android.text.style.UnderlineSpan;
import android.text.style.UpdateAppearance;

import java.lang.reflect.Method;
import java.util.regex.Pattern;

/**
 * 语法高亮下划线 Span：在部分主题下以下划线标示拼写/匹配区域，
 * 通过反射兼容不同 API 级别的 underlineTextColor 更新。
 */
public class ColorUnderlineSpan extends UnderlineSpan implements UpdateAppearance {
    public static final Pattern HEX_CODE_UNDERLINE_PATTERN = Pattern.compile("(?:\\s|[\";,:'*]|^)(#[A-Fa-f0-9]{6,8})+(?:\\s|[\";,:'*]|$)");

    private final int _color;
    private final float _thickness;

    public ColorUnderlineSpan(int color, Float thickness) {
        _color = color;
        _thickness = thickness == null ? 1.0f : thickness;
    }

    @Override
    @SuppressWarnings("JavaReflectionMemberAccess")
    public void updateDrawState(final TextPaint tp) {
        try {
            Method method = TextPaint.class.getMethod("setUnderlineText", Integer.TYPE, Float.TYPE);
            method.invoke(tp, _color, _thickness);
        } catch (Exception e) {
            tp.setUnderlineText(true);
        }
    }
}
