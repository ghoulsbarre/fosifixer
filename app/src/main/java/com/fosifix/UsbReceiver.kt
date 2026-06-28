package com.fosifix

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import java.util.Locale

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

        fun isTargetDevice(device: UsbDevice): Boolean {
            return device.vendorId == VENDOR_ID
        }

        fun formatProductId(productId: Int): String {
            return "0x${productId.toString(16).uppercase(Locale.US).padStart(4, '0')}"
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
