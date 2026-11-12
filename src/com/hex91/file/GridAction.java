package com.hex91.file;

import com.hex91.file.controller.*;
/**
 * 双列宫格弹窗的动作回调。
 *
 * <p>v2.0.0（O-15 刀3）：原来是 MainActivity 里的 {@code private interface}，
 * ArchiveController 也要弹同一个宫格菜单（压缩包条目的长按菜单），私有接口跨类用不了，
 * 所以提成顶层包内接口 —— 三处 {@code new GridAction() {...}} 的写法一个字都不用改。</p>
 */
public interface GridAction {
    void run(int act);
}
