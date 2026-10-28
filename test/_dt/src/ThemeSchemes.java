
/**
 * v1.48.0 主题色板定义 —— 由 tools/gen_themes.py 与 res/values/themes.xml 同步生成。
 * 顺序与 MT Manager 主题设置页一致（索引 0..21）。
 */
public final class ThemeSchemes {

    private ThemeSchemes() {
    }

    /** 色板名称 */
    public static final String[] NAMES = {
            "蓝色",
            "绿色",
            "琥珀",
            "珊瑚",
            "灰绿",
            "石墨",
            "曜石",
            "粉色",
            "正红",
            "紫罗兰",
            "深蓝",
            "湖蓝",
            "藏青",
            "靛蓝",
            "松石",
            "玫红",
            "古铜",
            "暗紫",
            "酒红",
            "橙红",
            "金黄",
            "深褐",
    };

    /** 色板主色（ARGB） */
    public static final int[] COLORS = {
        0xFF3E9AFB,
        0xFF61BC80,
        0xFFE3A23C,
        0xFFF16D6D,
        0xFF8E9587,
        0xFF626566,
        0xFF333033,
        0xFFFC5C99,
        0xFFFC0001,
        0xFF981FFF,
        0xFF032DA3,
        0xFF80D7CF,
        0xFF02304F,
        0xFF6A67A8,
        0xFF06837B,
        0xFFC4112C,
        0xFFA45C2D,
        0xFF45012A,
        0xFF4E000A,
        0xFFEB5F21,
        0xFFFAD16B,
        0xFF442E20,
    };

    public static int count() {
        return NAMES.length;
    }
}
