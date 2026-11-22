#!/usr/bin/env bash
# 测试入口共用判据（R-44）：run_core.sh 与 run_all.sh 必须对同一缺失环境给出同一结论。
#
#   用法： . "$(dirname "$0")/../tools/testlib.sh"
#
# 只定义函数，不改变 cwd、不设 set -e/-u、不引用工具链变量。
#
# 为什么单独一份：v2.110.0 之前 run_core.sh 判「缺 test/huangguo.apk → SKIP」，
# 而 run_all.sh 没有这套判据，同一 HEAD 下两个入口给出相反结论（run_core rc=0，
# run_all 90/7/4，7 个 FAIL 全是缺样本）。README 对外声称的「全量 97/0/4」指的是
# run_all.sh，于是那句数字在没有样本的机器上永远复现不出来。

# test_needs_apk <短类名> → 0 表示该用例直接读 test/huangguo.apk，缺样本时必须 SKIP 而非 FAIL。
# 必须逐个点名，不能拿「参数路径是否存在」当判据：绝大多数用例的 ARG 只是占位，
# 会对不存在的路径照样自给自足。
test_needs_apk() {
  case "$1" in
    KeyStoreUtilTest|SignSchemeTest|ArscPreviewTest|FileInspectTest|\
    SignPromptFlowTest|CustomSignE2ETest|SessionTest) return 0 ;;
  esac
  return 1
}

# test_needs_p12 <短类名> → 0 表示还要读 assets/hexfile.p12（内置密钥）。
test_needs_p12() {
  [ "$1" = "SessionTest" ]
}
