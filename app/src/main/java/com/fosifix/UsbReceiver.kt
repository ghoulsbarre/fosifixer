package com.fosifix

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build

class UsbReceiver(
    private val onAttached: (UsbDevice) -> Unit,
    private val onDetached: (UsbDevice) -> Unit,
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val device = intent.usbDevice() ?: return
        if (!isTargetDevice(device)) return

        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> onAttached(device)
            UsbManager.ACTION_USB_DEVICE_DETACHED -> onDetached(device)
        }
    }

    companion object {
        const val VENDOR_ID = 0x8888
        const val PRODUCT_ID = 0x1717

        fun isTargetDevice(device: UsbDevice): Boolean {
            return device.vendorId == VENDOR_ID && device.productId == PRODUCT_ID
        }

        private fun Intent.usbDevice(): UsbDevice? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
        }
    }
}
