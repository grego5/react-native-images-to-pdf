package com.margelo.nitro.imagespdf

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfoProvider

/**
 * Empty React package used to initialize the Nitro native library.
 * The ImagesToPdf Hybrid Object is exposed through Nitro, not as a React Native module.
 */
class NitroImagesToPdfPackage : BaseReactPackage() {
  override fun getModule(
    name: String,
    reactContext: ReactApplicationContext,
  ): NativeModule? = null

  override fun getReactModuleInfoProvider(): ReactModuleInfoProvider =
    ReactModuleInfoProvider { HashMap() }

  companion object {
    init {
      NitroImagesToPdfOnLoad.initializeNative()
    }
  }
}
