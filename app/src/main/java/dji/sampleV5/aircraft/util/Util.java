package dji.sampleV5.aircraft.util;


import android.content.Context;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;


import dji.v5.utils.common.ContextUtil;
import dji.v5.utils.common.LogUtils;

/**
 * 通用工具類，提供 map 集合轉換、儲存單位格式化與反射泛型實體建立等共用方法。
 */
public class Util {

    private final static String FILED_TYPE = "TYPE";
    private final static String TAG = Util.class.getSimpleName();

    private Util(){
        // 初始化某些內容
    }
    /**
     * 取得 map 集合 key 的 List 集合
     *
     */
    public static <K, V> List<K> getMapKeyList(Map<K, V> sourceMap) {
        List<K> keyList = new ArrayList<>();
        if (sourceMap == null || sourceMap.isEmpty()) {
            return keyList;
        }
        Set<K> keySet = sourceMap.keySet();
        for (K k : keySet) {
            keyList.add(k);
        }
        return keyList;
    }


    /**
     * 取得 map 集合 value 的 List 集合
     *
     */
    public static <K, V> List<V> getMapValueList(Map<K, V> sourceMap) {
        List<V> valueList = new ArrayList<>();
        if (sourceMap == null || sourceMap.isEmpty()) {
            return valueList;
        }
        Collection<V> values = sourceMap.values();
        for (V v : values) {
            valueList.add(v);
        }
        return valueList;
    }


    public static int getHeight(Context context) {
        DisplayMetrics dm = new DisplayMetrics();
        Display display = getResolution(context);
        display.getMetrics(dm);
        return dm.heightPixels;
    }


    public static Display getResolution(Context context) {
        return ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
    }


    /**
     * 剔除兩側空格後是否為空
     *
     * @param str
     * @return
     */
    public static boolean isBlank(String str) {
        return (str == null || str.trim().length() == 0);
    }

    public static boolean isNotBlank(String str) {
        return (str != null && str.trim().length() != 0);
    }

    public static <T> T getGenericInstance(Object obj, int i) {
        try {
            Object superClass = obj.getClass().getGenericSuperclass();
            if (superClass instanceof ParameterizedType) {
                Type type = ((ParameterizedType) superClass).getActualTypeArguments()[i];
                return ((Class<T>) type).newInstance();
            }
        } catch (InstantiationException e) {
            LogUtils.e(TAG ,e.getMessage());
        } catch (IllegalAccessException e) {
            LogUtils.e(TAG ,e.getMessage());
        }
        return null;
    }

    /**
     * 判斷是否為基本型別的包裝類別
     *
     * @param clz
     * @return
     */
    public static boolean isWrapClass(Class<?> clz) {
        try {
            return ((Class) clz.getField(FILED_TYPE).get(null)).isPrimitive();
        } catch (Exception e) {
            return false;
        }
    }

    public static String getDateStr(Date date) {
        String result = "";
        try {
            result = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(date);
        } catch (Exception e) {
            LogUtils.e(TAG ,e.getMessage());
        }
        return result;
    }

    public static String getString( int msg) {
        return ContextUtil.getContext().getResources().getString(msg);
    }

    /**
     * 回傳自適應顯示的儲存大小字串，數字最大保留 2 位，指定有效位數 4
     * @param size 原始儲存空間，單位 B
     * @return
     */
    public static String byte2AdaptiveUnitStrDefault(long size) {
        return byte2AdaptiveUnitStr(size, 2, 4);
    }

    /**
     * 回傳自適應顯示的儲存大小字串
     * @param size 原始儲存空間，單位 B
     * @param atMostDecimalPlaces 最大保留小數
     * @param sigFig 指定有效位數
     * @return
     */
    public static String byte2AdaptiveUnitStr(long size, int atMostDecimalPlaces, int sigFig) {
        int count = 0;
        int radix = 1024;
        double sizeDouble = size * 1.0;
        while (sizeDouble > radix) {
            sizeDouble /= radix;
            count++;
        }
        String unit = "?";
        switch (count) {
            case 3:
                unit = "G";
                break;
            case 2:
                unit = "M";
                break;
            case 1:
                unit = "K";
                break;
            case 0:
                unit = "B";
                break;
            default:
                break;
        }
        return String.format(Locale.US,"%s%s", roundToSigFigWithAtMostDecimal(sizeDouble, atMostDecimalPlaces, sigFig), unit);
    }


    /**
     * 回傳最大保留小數位數的指定有效位數數字對應字串。
     * 當顯示的有效數字位數超過最大保留小數位數時，依最大保留位數截斷數字。
     * @param num 原始數字，輸入不應小於 1
     * @param atMostDecimalPlaces 最大保留小數位數
     * @param sigFig 有效位數
     * @return
     */
    public static String roundToSigFigWithAtMostDecimal(double num, int atMostDecimalPlaces, int sigFig) {
        if (num < 1) {
            return "0";
        }
        double roundResult = roundToSignificantFigures(num, sigFig);
        int digitAfterZero = sigFig - (int) (Math.log10(Math.abs(roundResult))) - 1;
        String format = "%." + Math.min(digitAfterZero, atMostDecimalPlaces) + "f";
        return String.format(Locale.US, format, roundResult);
    }

    /**
     * 回傳指定有效位數的數字
     * @param num 原始數字
     * @param sigFig 指定有效位數
     * @return
     */
    public static double roundToSignificantFigures(double num, int sigFig) {
        if(num == 0) {
            return 0;
        }

        final double d = Math.ceil(Math.log10(num < 0 ? -num: num));
        final int power = sigFig - (int) d;

        final double magnitude = Math.pow(10, power);
        final long shifted = Math.round(num*magnitude);
        return shifted/magnitude;
    }

}


