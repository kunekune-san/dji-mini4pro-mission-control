package dji.sampleV5.aircraft.models

import androidx.fragment.app.FragmentActivity
import dji.v5.common.callback.CommonCallbacks
import dji.v5.manager.account.LoginInfo
import dji.v5.manager.account.LoginInfoUpdateListener
import dji.v5.manager.account.UserAccountManager

/**
 * Description : DJI 帳號登入相關 ViewModel。提供登入、登出、登入狀態監聽與驗證碼取得等功能。
 *
 * @author: Byte.Cai
 *  date : 2022/4/24
 *
 * Copyright (c) 2022, DJI All Rights Reserved.
 */
class LoginVM : DJIViewModel() {
    private val userAccountManager = UserAccountManager.getInstance()


    /** 使用指定 Activity 啟動 DJI 帳號登入流程。 */
    fun loginAccount(fragmentActivity: FragmentActivity, callbacks: CommonCallbacks.CompletionCallback) {
        userAccountManager.logInDJIUserAccount(fragmentActivity, false, callbacks)
    }

    /** 登出目前登入的 DJI 帳號。 */
    fun logoutAccount(callbacks: CommonCallbacks.CompletionCallback) {
        userAccountManager.logOutDJIUserAccount(callbacks)
    }

    /** 新增登入狀態變更的監聽。 */
    fun addLoginStateChangeListener(loginInfoUpdateListener: LoginInfoUpdateListener) {
        userAccountManager.addLoginInfoUpdateListener(loginInfoUpdateListener)
    }

    /** 清除所有登入狀態變更的監聽。 */
    fun clearAllLoginStateChangeListener() {
        userAccountManager.clearAllLoginInfoUpdateListener()
    }

    fun getLoginInfo(): LoginInfo {
        return userAccountManager.loginInfo
    }

    /** 使用帳號、密碼與驗證碼進行 DJI 帳號登入。 */
    fun userLogin(
        userName: String,
        password: String,
        verificationCode: String? = null,
        callbacks: CommonCallbacks.CompletionCallback,
    ) {
        userAccountManager.logInDJIUserAccount(userName, password, verificationCode, callbacks)
    }

    fun getVerificationCodeImageURL(): String {
        return userAccountManager.verificationCodeImageURL
    }


}