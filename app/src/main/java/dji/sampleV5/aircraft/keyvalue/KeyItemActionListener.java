package dji.sampleV5.aircraft.keyvalue;

/**
 * @author feel.feng
 * @time 2022/03/11 9:55 上午
 * @description:
 */

import org.jetbrains.annotations.Nullable;

/**
 * key 操作結果的回呼監聽介面，用於接收操作完成或資料變更的通知。
 *
 * @author feel.feng
 */
public interface KeyItemActionListener<T> {

    void actionChange(@Nullable T t);
}

