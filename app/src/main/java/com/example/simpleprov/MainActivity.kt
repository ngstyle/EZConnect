package com.example.simpleprov

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdManager.DiscoveryListener
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.Window
import android.widget.CheckBox
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.example.simpleprov.origin.R
import com.example.simpleprov.origin.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import timber.log.Timber

@SuppressLint("SetTextI18n")
class MainActivity : AppCompatActivity(), View.OnClickListener, CoroutineScope by MainScope() {
    private var emitStarted = false
    private var emitterTask: EmitterTask? = null
    var discoveryListener: DiscoveryListener? = null

    private lateinit var wifiManager: WifiManager
    private lateinit var nsdManager: NsdManager
    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel

    private var dotTimes = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        viewModel = ViewModelProvider(this).get(MainViewModel::class.java)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        nsdManager = getSystemService(NSD_SERVICE) as NsdManager

        initViewListener()
    }

    private fun initViewListener() {
//        binding.rememberCheckbox.setOnClickListener {
//            val text =
//                if (binding.rememberCheckbox.isChecked) binding.editTextPassphrase.text.toString() else ""
//            viewModel.rememberPass(binding.editTextSsid.text.toString(), text)
//        }

        binding.btnStart.isEnabled = false
        binding.btnStart.setOnClickListener(this)
        binding.unmaskPassphrase.setOnClickListener { v ->
            if ((v as CheckBox).isChecked) {
                binding.editTextPassphrase.inputType = InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                binding.editTextPassphrase.inputType = 129
            }
        }
    }

    private fun initializeDiscoveryListener() {
        var flag = false
        if (discoveryListener != null) {
            Timber.e("stopService method: $discoveryListener")
            nsdManager.stopServiceDiscovery(discoveryListener)
            flag = true
        }

        val resolveListener: NsdManager.ResolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // Called when the resolve fails. Use the error code to debug.
//                handler.removeCallbacks(mRunnable)
                Timber.e("Resolve failed: $errorCode")
                binding.tvNsdServiceInfo.removeCallbacks(mRunnable)
                binding.tvNsdServiceInfo.post {
                    binding.tvNsdServiceInfo.text = "服务连接失败: $errorCode"
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                Timber.e("Resolve Succeeded. $serviceInfo")
                binding.tvNsdServiceInfo.removeCallbacks(mRunnable)
                binding.tvNsdServiceInfo.post {
                    binding.tvNsdServiceInfo.text = "服务连接成功"
                }
            }
        }

        // Instantiate a new DiscoveryListener
        discoveryListener = object : DiscoveryListener {
            // Called as soon as service discovery begins.
            override fun onDiscoveryStarted(regType: String) {
                Timber.d("Service discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                // A service was found! Do something with it.
                Timber.d("Service discovery success: $service")
                nsdManager.resolveService(service, resolveListener)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                // When the network service is no longer available.
                // Internal bookkeeping code goes here.
                Timber.e("service lost: $service")
                binding.tvNsdServiceInfo.post {
                    binding.tvNsdServiceInfo.text = "service lost: $service"
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Timber.i("Discovery stopped: $serviceType, $discoveryListener")
                nsdManager.discoverServices(
                    "_ezconnect-prov._tcp",
                    NsdManager.PROTOCOL_DNS_SD,
                    discoveryListener
                )
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Timber.e("Discovery failed: Error code:$errorCode")
                nsdManager.stopServiceDiscovery(this)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Timber.e("Discovery failed: Error code:$errorCode")
                nsdManager.stopServiceDiscovery(this)
            }
        }

        if (!flag)
            nsdManager.discoverServices(
                "_ezconnect-prov._tcp",
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener
            )
    }

    override fun onResume() {
        super.onResume()
//        binding.tvDebug.text = "App Started"
        checkWiFi()
    }

//    val handler: Handler = object : Handler() {
//        override fun handleMessage(msg: Message) {
//            if (msg.what == 42) {
//                Log.d("MRVL", "ADI ASync task exited")
//                xmitStarted = false
//                binding.btnStart.text = "Start"
//                binding.tvDebug.text =
//                    "Please check indicators on the device.\n The device should have been provisioned.\n If not, please retry."
//            } else if (msg.what == 43) {
//                binding.tvDebug.text = "Information sent " + msg.arg1 / 2 + " times."
//            }
//            super.handleMessage(msg)
//        }
//    }

    private val mRunnable: Runnable = object : Runnable {
        override fun run() {
            val number = ++dotTimes % 3 + 1

            binding.tvDebug.text = "${dotTimes / 2} s"
            binding.tvNsdServiceInfo.text = "服务连接中" + ".".repeat(number)
            binding.tvNsdServiceInfo.postDelayed(this, 500)
        }
    }

    override fun onClick(v: View) {
        val ssidParam = wifiManager.connectionInfo.ssid
        val pass = binding.editTextPassphrase.text.toString()
        val key = binding.editTextDevKey.text.toString()
        val data = binding.editTextCustomData.text.toString()

        if (!validate(pass, key)) return

        // validate data
        if (!viewModel.validateCustomData(data)) {
            binding.tvDebug.text =
                "Invalid custom data. Custom data must be hexadecimal string with even length."
            return
        }

        // 重置
        if (emitterTask != null) emitterTask!!.resetStateMachine()
        binding.tvNsdServiceInfo.text = "服务连接中."
        dotTimes = 1
        binding.tvNsdServiceInfo.removeCallbacks(mRunnable)
        binding.tvNsdServiceInfo.postDelayed(mRunnable, 600)
        initializeDiscoveryListener()

        if (binding.rememberCheckbox.isChecked) {
            viewModel.rememberPass(
                binding.editTextSsid.text.toString(),
                binding.editTextPassphrase.text.toString()
            )
        } else {
            viewModel.rememberPass(binding.editTextSsid.text.toString(), "")
        }

        try {
            if (!emitStarted) {
                emitStarted = true
                binding.btnStart.text = getString(R.string.stop)

                emitterTask = EmitterTask(application).apply {
                    launch(Dispatchers.IO) {
                        execute(ssidParam, pass, key, data)
                        emitStarted = false
                        binding.btnStart.text = getString(R.string.start)
                    }
                }

            }
//            else {
//                xmitStarted = false
//                binding.btnStart.text = getString(R.string.start)
//            }
        } catch (err: Error) {
            Timber.e(err)
        }
    }

    private fun checkWiFi(): Int {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.shouldShowRequestPermissionRationale(this, "")

            Timber.i("未获取到定位权限")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                0
            )
        } else {
            if (!wifiManager.isWifiEnabled) {
                binding.tvDebug.text = getString(R.string.wifi_disabled)
                return -1
            } else if (wifiManager.connectionInfo.ssid.isEmpty()) {
                binding.tvDebug.text = getString(R.string.wifi_not_conn_to_network)
                return -1
            }
            binding.editTextSsid.setText(wifiManager.connectionInfo.ssid)
            binding.tvFrequency.text = formatFrequency(wifiManager.connectionInfo.frequency)

            val passBySsid = viewModel.getPassBySsid(binding.editTextSsid.text.toString())
            binding.rememberCheckbox.isChecked = passBySsid.isNotEmpty()
            binding.editTextPassphrase.setText(passBySsid)
            binding.btnStart.isEnabled = true
        }
        return 0
    }

    fun formatFrequency(frequency: Int): String {
        return if (frequency > 2400 && frequency < 2500) {
            "2.4GHz"
        } else if (frequency > 4900 && frequency < 5900) {
            "5GHz"
        } else {
            frequency.toString()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.tvNsdServiceInfo.removeCallbacks(mRunnable)
        nsdManager.stopServiceDiscovery(discoveryListener)
    }

    private fun validate(pass: String, key: String): Boolean {
        if (pass.isNotEmpty() && (pass.length < 8 || pass.length > 63)) {
            binding.tvDebug.text = getString(R.string.invalid_passphrase)
            return false
        }
        if (key.length !in 17 downTo 7) {
            binding.tvDebug.text = getString(R.string.invalid_device_key)
            return false
        }
        return true
    }

//    fun toHex(buf: ByteArray?): String {
//        if (buf == null) return ""
//        val result = StringBuffer(2 * buf.size)
//        for (i in buf.indices) {
//            appendHex(result, buf[i].toInt())
//        }
//        return result.toString()
//    }

//    private fun appendHex(sb: StringBuffer, b: Int) {
//        val HEX = "0123456789ABCDEF"
//        sb.append(HEX[b shr 4 and 0x0f]).append(HEX[b and 0x0f])
//    }


}