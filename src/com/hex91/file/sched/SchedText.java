package com.hex91.file.sched;

import android.content.Context;

import com.hex91.file.R;

/**
 * 定时任务结果码 → 界面文案（v2.109.0，F-14/96）。
 *
 * <p>为什么放在 sched 而不是 ui：{@link ScheduleReceiver} 在后台拿到结果后要立刻把
 * 「上一轮结果」写进任务表（{@code lastResult} 是持久化的展示串），它必须先翻成人话。
 * 依赖方向是 ui → sched，sched 不能反向 import ui 的映射类，所以这里直接吃
 * {@code Context} + 资源键 —— 与 {@code ScheduleReceiver} 已经在用的
 * {@code ctx.getString(R.string.…)} 是同一种做法。</p>
 *
 * <p>逻辑层（{@link ScheduleRunner}）只产出码与 ASCII 诊断串，不再拼中文。</p>
 */
public final class SchedText {

    private SchedText() {}

    /** 失败原因；成功返回空串，未知码回落到 ASCII 诊断串（便于日志对照） */
    public static String errorText(Context c, ScheduleRunner.Result r) {
        if (r == null || r.ok()) return "";
        switch (r.code) {
            case ScheduleRunner.Result.E_EMPTY_TASK:
                return c.getString(R.string.sched_err_empty_task);
            case ScheduleRunner.Result.E_UNKNOWN_ACTION:
                return c.getString(R.string.sched_err_unknown_action, r.str(0));
            case ScheduleRunner.Result.E_NO_TARGET:
                return c.getString(R.string.sched_err_no_target);
            case ScheduleRunner.Result.E_DANGEROUS:
                return c.getString(R.string.sched_err_dangerous, r.str(0));
            case ScheduleRunner.Result.E_NOT_EXIST:
                return c.getString(R.string.sched_err_not_exist, r.str(0));
            case ScheduleRunner.Result.E_NOT_DIR:
                return c.getString(R.string.sched_err_not_dir, r.str(0));
            case ScheduleRunner.Result.E_LIST_FAIL:
                return c.getString(R.string.sched_err_list_fail);
            case ScheduleRunner.Result.E_CLEAN_PARTIAL:
                return c.getString(R.string.sched_err_clean_partial, r.num(0));
            case ScheduleRunner.Result.E_BAD_DAYS:
                return c.getString(R.string.sched_err_bad_days);
            case ScheduleRunner.Result.E_EXPIRE_PARTIAL:
                return c.getString(R.string.sched_err_expire_partial, r.num(0));
            case ScheduleRunner.Result.E_NO_PARENT:
                return c.getString(R.string.sched_err_no_parent);
            case ScheduleRunner.Result.E_EMPTY_DIR:
                return c.getString(R.string.sched_err_empty_dir);
            case ScheduleRunner.Result.E_VAULT_ONLY:
                return c.getString(R.string.sched_err_vault_only);
            case ScheduleRunner.Result.E_ZIP_FAIL:
                return c.getString(R.string.sched_err_zip_fail, r.str(0));
            default:
                return r.error == null ? "" : r.error;
        }
    }
}
