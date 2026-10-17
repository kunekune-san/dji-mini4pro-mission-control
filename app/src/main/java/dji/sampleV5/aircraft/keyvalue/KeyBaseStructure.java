package dji.sampleV5.aircraft.keyvalue;


import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dji.sdk.keyvalue.key.DJIActionKeyInfo;
import dji.sdk.keyvalue.key.DJIKey;
import dji.sdk.keyvalue.key.DJIKeyInfo;
import dji.sdk.keyvalue.key.KeyTools;
import dji.v5.common.callback.CommonCallbacks;
import dji.v5.manager.KeyManager;
import dji.v5.utils.common.LogUtils;


/**
 * Key 基礎結構類別，封裝參數、結果、Listener 等通用狀態，並提供 Get／Set／Listen／Action 的基礎操作。
 */
public class KeyBaseStructure<P, R>   {

    private static final String TAG = KeyBaseStructure.class.getSimpleName();

    /**
     * 設定參數
     */
    protected P param;

    /**
     * 返回結果
     */
    protected R result;

    /**
     * 推送資料記錄
     */
    protected String listenRecord = "";

    /**
     * 推送 Listener 宿主
     */
    protected Object listenHolder;

    public int getComponetIndex() {
        return componetIndex;
    }

    public void setComponetIndex(int componetIndex) {
        this.componetIndex = componetIndex;
    }

    public int getSubComponetType() {
        return subComponetType;
    }

    public void setSubComponetType(int subComponetType) {
        this.subComponetType = subComponetType;
    }

    public int getSubComponetIndex() {
        return subComponetIndex;
    }

    public void setSubComponetIndex(int subComponetIndex) {
        this.subComponetIndex = subComponetIndex;
    }

    protected int componetIndex = -1;

    protected int subComponetType = -1;

    protected int subComponetIndex = -1;


    /**
     * 列舉列表
     */
    protected Map<String, List<EnumItem>> subItemMap = new HashMap<>();


    /**
     * 透過反射取得泛型類型資料並實例化
     */
    protected void initGenericInstance() {
        try {
            KeyItemHelper.INSTANCE.initClassData(param);
            KeyItemHelper.INSTANCE.initClassData(result);
            initSubItemData();
        } catch (Exception e) {
            LogUtils.e(TAG ,e.getMessage());
        }
    }

    /**
     * 如果為列舉或列舉巢狀列表，則初始化該列表資料
     */
    protected void initSubItemData() {
        if (param == null) {
            return;
        }
        subItemMap.clear();
        subItemMap.putAll(KeyItemHelper.INSTANCE.initSubItemData(param));
    }


    /**
     * 取得需要設定的參數實例
     *
     * @return
     */
    public P getParam() {
        return param;
    }

    /**
     * 取得參數映射列表
     *
     * @return
     */
    public Map<String, List<EnumItem>> getSubItemMap() {
        return subItemMap;
    }

    /**
     * 取得推送資料記錄
     *
     * @return
     */
    public String getListenRecord() {
        return listenRecord;
    }



    /**
     * 非同步 get
     *
     * @param keyInfo
     * @param getCallback
     */
    protected void get(DJIKeyInfo<R> keyInfo, CommonCallbacks.CompletionCallbackWithParam<R> getCallback) {
        DJIKey<R> key = createKey(keyInfo);
        KeyManager.getInstance().getValue(key, getCallback);
    }

    /**
     * 同步 get
     *
     * @param keyInfo
     * @return
     */
    protected R get(DJIKeyInfo<R> keyInfo) {
        DJIKey<R> key = createKey(keyInfo);
       return KeyManager.getInstance().getValue(key);

    }

    /**
     * 設定屬性
     *
     * @param keyInfo
     * @param setCallback
     */
    protected void set(DJIKeyInfo<P> keyInfo, P param, CommonCallbacks.CompletionCallback setCallback) {

        DJIKey<P> key = createKey(keyInfo);
        KeyManager.getInstance().setValue(key, param, setCallback);
    }



    /**
     * 設定 Listener
     *
     * @param keyInfo
     * @param listenHolder
     * @param listenCallback
     */
    protected void listen(DJIKeyInfo<R> keyInfo, Object listenHolder, CommonCallbacks.KeyListener<R> listenCallback) {
        this.listenHolder = listenHolder;

        DJIKey<R> key = createKey(keyInfo);
        KeyManager.getInstance().listen(key, listenHolder, listenCallback);
    }

    /**
     * 取消 Listener
     *
     * @param keyInfo
     * @param listenHolder
     */
    protected void cancelListen(DJIKeyInfo<R> keyInfo, Object listenHolder) {

        KeyManager.getInstance().cancelListen(createKey(keyInfo), listenHolder);
    }

    /**
     * 帶參數 action
     *
     * @param keyInfo
     * @param param
     * @param actonCallback
     */
    protected void action(DJIActionKeyInfo<P, R> keyInfo, P param, CommonCallbacks.CompletionCallbackWithParam<R> actonCallback) {

        DJIKey.ActionKey<P,R> key = createActionKey(keyInfo);
        KeyManager.getInstance().performAction(key, param, actonCallback);
    }

    protected DJIKey.ActionKey<P,R> createActionKey(DJIActionKeyInfo<P,R> keyInfo) {
        DJIKey.ActionKey<P,R> key = null;
        key = KeyTools.createKey(keyInfo, 0, getComponetIndex(),getSubComponetType(), getSubComponetIndex());
        return key;
    }

    protected<Parame> DJIKey<Parame> createKey(DJIKeyInfo<Parame> keyInfo ) {
        DJIKey<Parame> key = null;
        key = KeyTools.createKey(keyInfo, 0 , getComponetIndex(),getSubComponetType(), getSubComponetIndex());
        return key;
    }

}
