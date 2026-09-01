package com.termux.shared.theme;
import android.content.Context;
import android.content.res.TypedArray;
public class ThemeUtils {
  public static final int ATTR_TEXT_COLOR_PRIMARY = android.R.attr.textColorPrimary;
  public static final int ATTR_TEXT_COLOR_SECONDARY = android.R.attr.textColorSecondary;
  public static final int ATTR_TEXT_COLOR = android.R.attr.textColor;
  public static final int ATTR_TEXT_COLOR_LINK = android.R.attr.textColorLink;
  public static boolean isNightModeEnabled(Context c) {
    return c!=null && (c.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
  }
  public static boolean shouldEnableDarkTheme(Context c, String n) { return isNightModeEnabled(c); }
  public static int getTextColorPrimary(Context c) { return getSystemAttrColor(c, ATTR_TEXT_COLOR_PRIMARY); }
  public static int getTextColorSecondary(Context c) { return getSystemAttrColor(c, ATTR_TEXT_COLOR_SECONDARY); }
  public static int getTextColor(Context c) { return getSystemAttrColor(c, ATTR_TEXT_COLOR); }
  public static int getTextColorLink(Context c) { return getSystemAttrColor(c, ATTR_TEXT_COLOR_LINK); }
  public static int getSystemAttrColor(Context c, int attr) { return getSystemAttrColor(c, attr, 0); }
  public static int getSystemAttrColor(Context c, int attr, int def) {
    TypedArray a = c.getTheme().obtainStyledAttributes(new int[]{attr});
    int col = a.getColor(0, def); a.recycle(); return col;
  }
}
