package com.example.myhook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class MainHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.log("MyGitHubHook: Loaded app -> ${lpparam.packageName}")

        // Example: Hook Activity.onResume() in any scoped app
        XposedHelpers.findAndHookMethod(
            "android.app.Activity",
            lpparam.classLoader,
            "onResume",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activityName = param.thisObject.javaClass.name
                    XposedBridge.log("MyGitHubHook: Resumed $activityName")
                }
            }
        )
    }
}
